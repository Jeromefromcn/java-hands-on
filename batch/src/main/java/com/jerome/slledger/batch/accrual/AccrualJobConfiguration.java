package com.jerome.slledger.batch.accrual;

import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.accrual.AccrualCandidates;
import com.jerome.slledger.application.accrual.AccrueDay;
import com.jerome.slledger.application.accrual.BookAccruals;
import com.jerome.slledger.application.accrual.BookResult;
import com.jerome.slledger.application.accrual.Slice;
import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.batch.support.FailurePoint;
import com.jerome.slledger.batch.support.KeysetReader;
import com.jerome.slledger.batch.support.RunSummaryListener;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
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

  /** Context keys the writer keeps its running totals under, saved with every chunk. */
  public static final String INSERTED = "accrual.inserted";

  public static final String PRESENT = "accrual.alreadyPresent";

  @Bean
  Job dailyAccrual(JobRepository repository, Step accrualStep, BatchRunMetrics metrics) {
    return new JobBuilder(JOB, repository)
        .validator(
            new DefaultJobParametersValidator(
                new String[] {"businessDate"}, new String[] {"rerun", "fencingToken"}))
        .listener(new RunSummaryListener(metrics))
        .start(accrualStep)
        .build();
  }

  @Bean
  Step accrualStep(
      JobRepository repository,
      PlatformTransactionManager transactions,
      BatchSettings settings,
      @Qualifier("accrualReader") ItemStreamReader<AccrualCandidate> accrualReader,
      ItemProcessor<AccrualCandidate, AccrualEntry> accrualProcessor,
      ItemWriter<AccrualEntry> accrualWriter) {
    return new StepBuilder(STEP, repository)
            .<AccrualCandidate, AccrualEntry>chunk(settings.chunkSize())
            .transactionManager(transactions)
            .reader(accrualReader)
            .processor(accrualProcessor)
            .writer(accrualWriter)
            .stream(accrualReader)
            .build();
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
      BookAccruals book, FailurePoint failures, @Value("#{stepExecution}") StepExecution step) {
    return chunk -> {
      failures.beforeWrite(step.getStepName(), chunk.getItems().getFirst().position().value());
      BookResult result = book.book(List.copyOf(chunk.getItems()));
      var context = step.getExecutionContext();
      context.putLong(INSERTED, context.getLong(INSERTED, 0L) + result.inserted());
      context.putLong(PRESENT, context.getLong(PRESENT, 0L) + result.alreadyPresent());
    };
  }
}
