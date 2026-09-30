package com.jerome.slledger.batch.accrual;

import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.accrual.AccrualCandidates;
import com.jerome.slledger.application.accrual.AccrueDay;
import com.jerome.slledger.application.accrual.BookAccruals;
import com.jerome.slledger.application.accrual.BookResult;
import com.jerome.slledger.application.accrual.CorruptPositionException;
import com.jerome.slledger.application.accrual.PartitionScheme;
import com.jerome.slledger.application.accrual.RecordSkip;
import com.jerome.slledger.application.accrual.Slice;
import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.batch.support.FailurePoint;
import com.jerome.slledger.batch.support.KeysetReader;
import com.jerome.slledger.batch.support.RunSummaryListener;
import com.jerome.slledger.batch.support.StepDefaults;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.error.DomainException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The daily accrual: active positions, in pages by key, to entries, in chunks.
 *
 * <p>Reader, processor and writer are step-scoped: each step execution gets its own, built from the
 * job's parameters (the business date) and the step's execution context (the slice a partition
 * owns). A partition's worker runs the same {@link Step} object on several threads at once, and a
 * reader that remembers its place cannot be shared between them.
 *
 * <p>The writer is idempotent by the store's primary key, so the cursor is an optimisation of a
 * restart, not what makes it safe: a run that read a chunk again would book nothing twice.
 */
@Configuration(proxyBeanMethods = false)
public class AccrualJobConfiguration {

  public static final String JOB = "dailyAccrual";
  public static final String STEP = "accrual";
  public static final String WORKER_STEP = "accrualWorker";

  /** Context keys the writer keeps its running totals under, saved with every chunk. */
  public static final String INSERTED = "accrual.inserted";

  public static final String PRESENT = "accrual.alreadyPresent";

  @Bean
  Job dailyAccrual(
      JobRepository repository,
      @Qualifier("accrualStep") Step accrualStep,
      BatchRunMetrics metrics,
      BookAccruals book) {
    return new JobBuilder(JOB, repository)
        .validator(
            new DefaultJobParametersValidator(
                new String[] {"businessDate"}, new String[] {"rerun", "fencingToken"}))
        .listener(new FenceClaimListener(book))
        .listener(new RunSummaryListener(metrics))
        .start(accrualStep)
        .build();
  }

  /**
   * The accrual step. Unpartitioned, it is the chunk step itself under the name {@code accrual}.
   * Partitioned, {@code accrual} is the manager: it asks {@link AccrualPartitioner} for slices and
   * runs the same chunk step, named {@code accrualWorker}, once per slice on the worker threads.
   * The manager's counts are the sum of its partitions', and the step ends when the slowest one
   * does.
   */
  @Bean
  Step accrualStep(
      JobRepository repository,
      PlatformTransactionManager transactions,
      BatchSettings settings,
      @Qualifier("accrualReader") ItemStreamReader<AccrualCandidate> accrualReader,
      ItemProcessor<AccrualCandidate, AccrualEntry> accrualProcessor,
      ItemWriter<AccrualEntry> accrualWriter,
      SkipListener<AccrualCandidate, AccrualEntry> accrualSkipListener,
      Partitioner accrualPartitioner,
      AccrualPartitionPool pool) {
    if (settings.partitionScheme() == PartitionScheme.NONE) {
      return chunkStep(
          STEP,
          repository,
          transactions,
          settings,
          accrualReader,
          accrualProcessor,
          accrualWriter,
          accrualSkipListener);
    }
    Step worker =
        chunkStep(
            WORKER_STEP,
            repository,
            transactions,
            settings,
            accrualReader,
            accrualProcessor,
            accrualWriter,
            accrualSkipListener);
    return new StepBuilder(STEP, repository)
        .partitioner(WORKER_STEP, accrualPartitioner)
        .step(worker)
        .taskExecutor(pool.executor())
        .gridSize(settings.partitions())
        .build();
  }

  private static Step chunkStep(
      String name,
      JobRepository repository,
      PlatformTransactionManager transactions,
      BatchSettings settings,
      ItemStreamReader<AccrualCandidate> reader,
      ItemProcessor<AccrualCandidate, AccrualEntry> processor,
      ItemWriter<AccrualEntry> writer,
      SkipListener<AccrualCandidate, AccrualEntry> skipListener) {
    ChunkOrientedStepBuilder<AccrualCandidate, AccrualEntry> step =
        new StepBuilder(name, repository)
                .<AccrualCandidate, AccrualEntry>chunk(settings.chunkSize())
                .transactionManager(transactions)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .stream(reader)
                .skipListener(skipListener);
    // A corrupt row and a refusal of the domain belong to one position: skipped, to the limit.
    return StepDefaults.faultTolerant(
            step,
            settings.skipLimit(),
            settings.retryLimit(),
            CorruptPositionException.class,
            DomainException.class)
        .build();
  }

  @Bean
  @StepScope
  Partitioner accrualPartitioner(
      AccrualCandidates candidates,
      BatchSettings settings,
      @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
    return new AccrualPartitioner(
        candidates, businessDate, settings.partitionScheme(), settings.partitions());
  }

  /** Threads that run the partitions; a holder type so no other component picks it up by type. */
  @Bean(destroyMethod = "close")
  AccrualPartitionPool accrualPartitionPool(BatchSettings settings) {
    return new AccrualPartitionPool(settings.workerThreads());
  }

  /** Writes every skipped position to {@code batch_skip}, in a transaction of its own. */
  @Bean
  @StepScope
  SkipListener<AccrualCandidate, AccrualEntry> accrualSkipListener(
      RecordSkip skips, @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
    String scope = businessDate.toString();
    return new SkipListener<>() {
      @Override
      public void onSkipInRead(Throwable t) {
        skips.record(JOB, scope, "read:" + t.getClass().getSimpleName(), "read", describe(t));
      }

      @Override
      public void onSkipInProcess(AccrualCandidate item, Throwable t) {
        skips.record(JOB, scope, item.positionId(), "process", describe(t));
      }

      @Override
      public void onSkipInWrite(AccrualEntry item, Throwable t) {
        skips.record(JOB, scope, item.position().value(), "write", describe(t));
      }
    };
  }

  private static String describe(Throwable t) {
    return t.getClass().getSimpleName() + ": " + t.getMessage();
  }

  @Bean
  @StepScope
  ItemStreamReader<AccrualCandidate> accrualReader(
      AccrualCandidates candidates,
      BatchSettings settings,
      @Value("#{jobParameters['businessDate']}") LocalDate businessDate,
      @Value("#{stepExecutionContext['slice.counterparty']}") String counterparty,
      @Value("#{stepExecutionContext['slice.idFrom']}") String idFrom,
      @Value("#{stepExecutionContext['slice.idTo']}") String idTo) {
    Slice slice =
        new Slice(
            Optional.ofNullable(counterparty),
            Optional.ofNullable(idFrom),
            Optional.ofNullable(idTo));
    return new KeysetReader<>(
        "accrual",
        settings.chunkSize(),
        after -> candidates.page(businessDate, slice, after, settings.chunkSize()),
        AccrualCandidate::positionId);
  }

  @Bean
  @StepScope
  ItemProcessor<AccrualCandidate, AccrualEntry> accrualProcessor(
      @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
    return candidate -> AccrueDay.accrue(candidate, businessDate);
  }

  @Bean
  @StepScope
  ItemWriter<AccrualEntry> accrualWriter(
      BookAccruals book,
      FailurePoint failures,
      @Value("#{stepExecution}") StepExecution step,
      @Value("#{jobParameters['businessDate']}") LocalDate businessDate,
      @Value("#{jobParameters['fencingToken']}") Long fencingToken) {
    // A run launched under a lease carries its token; every chunk checks it in its own transaction.
    Optional<BookAccruals.Fenced> fenced =
        Optional.ofNullable(fencingToken)
            .map(token -> new BookAccruals.Fenced(RunDailyAccrual.resource(businessDate), token));
    return chunk -> {
      failures.beforeWrite(step.getStepName(), chunk.getItems().getFirst().position().value());
      BookResult result = book.book(List.copyOf(chunk.getItems()), fenced);
      var context = step.getExecutionContext();
      context.putLong(INSERTED, context.getLong(INSERTED, 0L) + result.inserted());
      context.putLong(PRESENT, context.getLong(PRESENT, 0L) + result.alreadyPresent());
    };
  }
}
