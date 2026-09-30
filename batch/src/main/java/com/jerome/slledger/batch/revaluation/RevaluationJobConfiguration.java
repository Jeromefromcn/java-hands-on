package com.jerome.slledger.batch.revaluation;

import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.accrual.CorruptPositionException;
import com.jerome.slledger.application.accrual.RecordSkip;
import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.revaluation.RaiseMarginCalls;
import com.jerome.slledger.application.revaluation.RevaluationCandidates;
import com.jerome.slledger.application.revaluation.Revalue;
import com.jerome.slledger.batch.accrual.BatchSettings;
import com.jerome.slledger.batch.support.KeysetReader;
import com.jerome.slledger.batch.support.RunSummaryListener;
import com.jerome.slledger.batch.support.StepDefaults;
import com.jerome.slledger.domain.error.DomainException;
import com.jerome.slledger.domain.instrument.Isin;
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
 * Revaluing one security's open positions at one price version, in chunks: the batch form of what
 * {@code ApplyPriceUpdate} does inline, for a security with too many positions to read in one
 * transaction.
 *
 * <p>Same skeleton as the accrual (a keyset reader with a saved cursor, a per-item processor, a
 * chunked writer, the shared skip and retry rules) with its own three parts: what is read (one
 * security's positions), what is decided (a margin call or nothing) and what is written (outbox
 * rows behind a marker). Nothing is inherited; the shared parts are composed.
 *
 * <p>The job instance is (security, price version): asking twice for the same update resumes or
 * reports it done, and a per-position marker keeps a chunk that runs again from raising its calls
 * twice.
 */
@Configuration(proxyBeanMethods = false)
public class RevaluationJobConfiguration {

  public static final String JOB = "revaluation";
  public static final String STEP = "revalue";
  public static final String RAISED = "revaluation.raised";
  public static final String ALREADY_RAISED = "revaluation.alreadyRaised";

  @Bean
  Job revaluation(
      JobRepository repository,
      @Qualifier("revalueStep") Step revalueStep,
      BatchRunMetrics metrics) {
    return new JobBuilder(JOB, repository)
        .validator(
            new DefaultJobParametersValidator(
                new String[] {"isin", "priceVersion"}, new String[] {"rerun"}))
        .listener(new RunSummaryListener(metrics))
        .start(revalueStep)
        .build();
  }

  @Bean
  Step revalueStep(
      JobRepository repository,
      PlatformTransactionManager transactions,
      BatchSettings settings,
      @Qualifier("revaluationReader") ItemStreamReader<AccrualCandidate> reader,
      ItemProcessor<AccrualCandidate, MarginCallRaised> revaluationProcessor,
      ItemWriter<MarginCallRaised> revaluationWriter,
      SkipListener<AccrualCandidate, MarginCallRaised> revaluationSkipListener) {
    ChunkOrientedStepBuilder<AccrualCandidate, MarginCallRaised> step =
        new StepBuilder(STEP, repository)
                .<AccrualCandidate, MarginCallRaised>chunk(settings.chunkSize())
                .transactionManager(transactions)
                .reader(reader)
                .processor(revaluationProcessor)
                .writer(revaluationWriter)
                .stream(reader)
                .skipListener(revaluationSkipListener);
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
  ItemStreamReader<AccrualCandidate> revaluationReader(
      RevaluationCandidates candidates,
      BatchSettings settings,
      @Value("#{jobParameters['isin']}") String isin) {
    Isin security = new Isin(isin);
    return new KeysetReader<>(
        "revaluation",
        settings.chunkSize(),
        after -> candidates.page(security, after, settings.chunkSize()),
        AccrualCandidate::positionId);
  }

  /** A position that is covered has no call: returning null filters it out of the chunk. */
  @Bean
  @StepScope
  ItemProcessor<AccrualCandidate, MarginCallRaised> revaluationProcessor(Revalue revalue) {
    return candidate -> revalue.revalue(candidate).orElse(null);
  }

  @Bean
  @StepScope
  ItemWriter<MarginCallRaised> revaluationWriter(
      RaiseMarginCalls raise,
      @Value("#{jobParameters['priceVersion']}") Long priceVersion,
      @Value("#{stepExecution}") StepExecution step) {
    return chunk -> {
      int raised = raise.raise(java.util.List.copyOf(chunk.getItems()), priceVersion);
      var context = step.getExecutionContext();
      context.putLong(RAISED, context.getLong(RAISED, 0L) + raised);
      context.putLong(
          ALREADY_RAISED, context.getLong(ALREADY_RAISED, 0L) + (chunk.getItems().size() - raised));
    };
  }

  @Bean
  @StepScope
  SkipListener<AccrualCandidate, MarginCallRaised> revaluationSkipListener(
      RecordSkip skips,
      @Value("#{jobParameters['isin']}") String isin,
      @Value("#{jobParameters['priceVersion']}") Long priceVersion) {
    String scope = isin + "@" + priceVersion;
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
      public void onSkipInWrite(MarginCallRaised item, Throwable t) {
        skips.record(JOB, scope, item.position().value(), "write", describe(t));
      }
    };
  }

  private static String describe(Throwable t) {
    return t.getClass().getSimpleName() + ": " + t.getMessage();
  }
}
