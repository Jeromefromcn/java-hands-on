package com.jerome.slledger.batch.support;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/**
 * A step that runs one piece of work and saves its counts in the step's execution context.
 *
 * <p>Without a transaction of its own: Spring Batch wraps a tasklet in one by default, and the work
 * here (a purge in many batches, a ladder of attempts) opens the short transactions it wants, which
 * a surrounding one would join and turn back into one long transaction.
 */
public final class Tasklets {

  private Tasklets() {}

  public static Step step(
      String name,
      JobRepository repository,
      PlatformTransactionManager transactions,
      Supplier<Map<String, Long>> work) {
    return new StepBuilder(name, repository)
        .tasklet(
            (contribution, chunkContext) -> {
              var context = contribution.getStepExecution().getExecutionContext();
              work.get().forEach(context::putLong);
              return RepeatStatus.FINISHED;
            },
            transactions)
        .transactionAttribute(
            new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED))
        .build();
  }
}
