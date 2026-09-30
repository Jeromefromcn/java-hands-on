package com.jerome.slledger.batch.revaluation;

import com.jerome.slledger.application.revaluation.RevaluationLauncher;
import com.jerome.slledger.domain.instrument.Isin;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;

/**
 * Launches the revaluation of one security at one price version. The instance is (security,
 * version): a run that failed is resumed from its saved cursor by launching it again, one that
 * completed is reported completed, and one that is running elsewhere is not started.
 */
public final class RunRevaluation implements RevaluationLauncher {

  private final JobOperator operator;
  private final Job job;

  public RunRevaluation(JobOperator operator, Job job) {
    this.operator = operator;
    this.job = job;
  }

  @Override
  public Result launch(Isin security, long priceVersion) {
    try {
      JobExecution execution =
          operator.run(
              job,
              new JobParametersBuilder()
                  .addString("isin", security.value())
                  .addLong("priceVersion", priceVersion)
                  .toJobParameters());
      return execution.getStatus().name().equals("COMPLETED") ? Result.COMPLETED : Result.FAILED;
    } catch (JobInstanceAlreadyCompleteException e) {
      return Result.COMPLETED;
    } catch (JobExecutionAlreadyRunningException e) {
      return Result.NOT_STARTED;
    } catch (JobRestartException | InvalidJobParametersException e) {
      throw new IllegalStateException(
          "The revaluation of %s at version %d could not be launched"
              .formatted(security.value(), priceVersion),
          e);
    }
  }
}
