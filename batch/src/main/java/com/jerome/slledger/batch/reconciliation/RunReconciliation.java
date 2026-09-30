package com.jerome.slledger.batch.reconciliation;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.OptionalLong;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;

/** Launches the reconciliation of one snapshot on one business date. */
public final class RunReconciliation {

  public enum Status {
    COMPLETED,
    FAILED,
    /** This (date, snapshot) already completed; nothing was started. */
    ALREADY_DONE,
    ALREADY_RUNNING
  }

  public record Outcome(Status status, JobExecution execution) {}

  private final JobOperator operator;
  private final Job job;

  public RunReconciliation(JobOperator operator, Job job) {
    this.operator = operator;
    this.job = job;
  }

  public Outcome run(LocalDate businessDate, String snapshotId, Path file) {
    return run(businessDate, snapshotId, file, OptionalLong.empty());
  }

  public Outcome run(LocalDate businessDate, String snapshotId, Path file, OptionalLong rerun) {
    JobParametersBuilder parameters =
        new JobParametersBuilder()
            .addLocalDate("businessDate", businessDate)
            .addString("snapshotId", snapshotId)
            .addString("snapshotFile", file.toAbsolutePath().toString(), false);
    rerun.ifPresent(n -> parameters.addLong("rerun", n));
    try {
      JobExecution execution = operator.run(job, parameters.toJobParameters());
      return new Outcome(
          execution.getStatus().name().equals("COMPLETED") ? Status.COMPLETED : Status.FAILED,
          execution);
    } catch (JobInstanceAlreadyCompleteException e) {
      return new Outcome(Status.ALREADY_DONE, null);
    } catch (JobExecutionAlreadyRunningException e) {
      return new Outcome(Status.ALREADY_RUNNING, null);
    } catch (JobRestartException | InvalidJobParametersException e) {
      throw new IllegalStateException(
          "The reconciliation of %s could not be launched".formatted(snapshotId), e);
    }
  }
}
