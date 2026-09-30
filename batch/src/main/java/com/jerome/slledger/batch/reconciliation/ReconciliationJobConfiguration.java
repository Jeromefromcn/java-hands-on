package com.jerome.slledger.batch.reconciliation;

import com.jerome.slledger.application.accrual.CorruptPositionException;
import com.jerome.slledger.application.accrual.RecordSkip;
import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.reconciliation.BreakClassifier;
import com.jerome.slledger.application.reconciliation.CustodianHolding;
import com.jerome.slledger.application.reconciliation.HoldingComparison;
import com.jerome.slledger.application.reconciliation.HoldingPair;
import com.jerome.slledger.application.reconciliation.LoadHoldings;
import com.jerome.slledger.application.reconciliation.MalformedHoldingException;
import com.jerome.slledger.application.reconciliation.ReconBreak;
import com.jerome.slledger.application.reconciliation.SaveBreaks;
import com.jerome.slledger.batch.accrual.BatchSettings;
import com.jerome.slledger.batch.support.CsvLinesReader;
import com.jerome.slledger.batch.support.KeysetReader;
import com.jerome.slledger.batch.support.RunSummaryListener;
import com.jerome.slledger.batch.support.StepDefaults;
import java.nio.file.Path;
import java.time.LocalDate;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.listener.SkipListener;
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
 * The reconciliation: a third job with its own inputs, not a part of the accrual. Two steps.
 *
 * <ol>
 *   <li>{@code loadSnapshot}: the custodian's CSV to {@code custodian_holding}. A line that is not
 *       a valid holding is skipped and recorded, and its position is left out of step 2.
 *   <li>{@code compare}: the ledger's holdings against the snapshot, by a full outer join, keyset
 *       paged; each pair is classified and each break recorded once.
 * </ol>
 *
 * <p>The job instance is (business date, snapshot id). The file's path is a non-identifying
 * parameter: where a snapshot is kept is not what makes it a different snapshot.
 */
@Configuration(proxyBeanMethods = false)
public class ReconciliationJobConfiguration {

  public static final String JOB = "reconciliation";
  public static final String LOAD_STEP = "loadSnapshot";
  public static final String COMPARE_STEP = "compare";
  public static final String BREAKS_RECORDED = "reconciliation.breaksRecorded";

  @Bean
  Job reconciliation(
      JobRepository repository,
      @Qualifier("loadSnapshotStep") Step loadSnapshotStep,
      @Qualifier("compareStep") Step compareStep,
      BatchRunMetrics metrics) {
    return new JobBuilder(JOB, repository)
        .validator(
            new DefaultJobParametersValidator(
                new String[] {"businessDate", "snapshotId", "snapshotFile"},
                new String[] {"rerun"}))
        .listener(new RunSummaryListener(metrics))
        .start(loadSnapshotStep)
        .next(compareStep)
        .build();
  }

  @Bean
  Step loadSnapshotStep(
      JobRepository repository,
      PlatformTransactionManager transactions,
      BatchSettings settings,
      @Qualifier("snapshotReader") ItemStreamReader<CsvLinesReader.Line> reader,
      ItemProcessor<CsvLinesReader.Line, CustodianHolding> holdingProcessor,
      ItemWriter<CustodianHolding> holdingWriter,
      @Qualifier("snapshotSkipListener")
          SkipListener<CsvLinesReader.Line, CustodianHolding> skipListener) {
    ChunkOrientedStepBuilder<CsvLinesReader.Line, CustodianHolding> step =
        new StepBuilder(LOAD_STEP, repository)
                .<CsvLinesReader.Line, CustodianHolding>chunk(settings.chunkSize())
                .transactionManager(transactions)
                .reader(reader)
                .processor(holdingProcessor)
                .writer(holdingWriter)
                .stream(reader)
                .skipListener(skipListener);
    return StepDefaults.faultTolerant(
            step, settings.skipLimit(), settings.retryLimit(), MalformedHoldingException.class)
        .build();
  }

  @Bean
  @StepScope
  ItemStreamReader<CsvLinesReader.Line> snapshotReader(
      @Value("#{jobParameters['snapshotFile']}") String snapshotFile) {
    return new CsvLinesReader("snapshot", Path.of(snapshotFile));
  }

  @Bean
  @StepScope
  ItemProcessor<CsvLinesReader.Line, CustodianHolding> holdingProcessor() {
    return line -> CustodianHolding.parse(line.number(), line.text());
  }

  @Bean
  @StepScope
  ItemWriter<CustodianHolding> holdingWriter(
      LoadHoldings load, @Value("#{jobParameters['snapshotId']}") String snapshotId) {
    return chunk -> load.load(snapshotId, java.util.List.copyOf(chunk.getItems()));
  }

  /**
   * A refused line is recorded under its position (so the compare step leaves it out) or its line.
   */
  @Bean
  @StepScope
  SkipListener<CsvLinesReader.Line, CustodianHolding> snapshotSkipListener(
      RecordSkip skips, @Value("#{jobParameters['snapshotId']}") String snapshotId) {
    return new SkipListener<>() {
      @Override
      public void onSkipInProcess(CsvLinesReader.Line item, Throwable t) {
        String key =
            t instanceof MalformedHoldingException malformed
                ? malformed.itemKey()
                : "line:" + item.number();
        skips.record(JOB, snapshotId, key, "process", t.getMessage());
      }
    };
  }

  @Bean
  Step compareStep(
      JobRepository repository,
      PlatformTransactionManager transactions,
      BatchSettings settings,
      @Qualifier("comparisonReader") ItemStreamReader<HoldingPair> reader,
      ItemProcessor<HoldingPair, ReconBreak> breakProcessor,
      ItemWriter<ReconBreak> breakWriter,
      @Qualifier("comparisonSkipListener") SkipListener<HoldingPair, ReconBreak> skipListener) {
    ChunkOrientedStepBuilder<HoldingPair, ReconBreak> step =
        new StepBuilder(COMPARE_STEP, repository)
                .<HoldingPair, ReconBreak>chunk(settings.chunkSize())
                .transactionManager(transactions)
                .reader(reader)
                .processor(breakProcessor)
                .writer(breakWriter)
                .stream(reader)
                .skipListener(skipListener);
    return StepDefaults.faultTolerant(
            step, settings.skipLimit(), settings.retryLimit(), CorruptPositionException.class)
        .build();
  }

  @Bean
  @StepScope
  ItemStreamReader<HoldingPair> comparisonReader(
      HoldingComparison comparison,
      BatchSettings settings,
      @Value("#{jobParameters['snapshotId']}") String snapshotId,
      @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
    return new KeysetReader<>(
        "comparison",
        settings.chunkSize(),
        after -> comparison.page(snapshotId, businessDate, after, settings.chunkSize()),
        HoldingPair::positionId);
  }

  /** A pair that agrees is no break: null filters it out of the chunk. */
  @Bean
  @StepScope
  ItemProcessor<HoldingPair, ReconBreak> breakProcessor(
      BreakClassifier classifier,
      @Value("#{jobParameters['snapshotId']}") String snapshotId,
      @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
    return pair -> classifier.classify(snapshotId, businessDate, pair).orElse(null);
  }

  @Bean
  @StepScope
  ItemWriter<ReconBreak> breakWriter(
      SaveBreaks save, @Value("#{stepExecution}") StepExecution step) {
    return chunk -> {
      int recorded = save.save(java.util.List.copyOf(chunk.getItems()));
      var context = step.getExecutionContext();
      context.putLong(BREAKS_RECORDED, context.getLong(BREAKS_RECORDED, 0L) + recorded);
    };
  }

  @Bean
  @StepScope
  SkipListener<HoldingPair, ReconBreak> comparisonSkipListener(
      RecordSkip skips, @Value("#{jobParameters['snapshotId']}") String snapshotId) {
    return new SkipListener<>() {
      @Override
      public void onSkipInProcess(HoldingPair item, Throwable t) {
        skips.record(JOB, snapshotId, "compare:" + item.positionId(), "process", t.getMessage());
      }
    };
  }
}
