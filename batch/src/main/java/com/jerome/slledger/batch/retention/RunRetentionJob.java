package com.jerome.slledger.batch.retention;

import java.time.LocalDate;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;

/** Launches the retention job for a date: once a date, and a failed date resumes. */
public final class RunRetentionJob {

  public enum Status {
    COMPLETED,
    FAILED,
    ALREADY_DONE,
    ALREADY_RUNNING
  }

  public record Outcome(Status status, JobExecution execution) {}

  private final JobOperator operator;
  private final Job job;

  public RunRetentionJob(JobOperator operator, Job job) {
    this.operator = operator;
    this.job = job;
  }

  public Outcome run(LocalDate businessDate) {
    try {
      JobExecution execution =
          operator.run(
              job,
              new JobParametersBuilder()
                  .addLocalDate("businessDate", businessDate)
                  .toJobParameters());
      return new Outcome(
          execution.getStatus().name().equals("COMPLETED") ? Status.COMPLETED : Status.FAILED,
          execution);
    } catch (JobInstanceAlreadyCompleteException e) {
      return new Outcome(Status.ALREADY_DONE, null);
    } catch (JobExecutionAlreadyRunningException e) {
      return new Outcome(Status.ALREADY_RUNNING, null);
    } catch (JobRestartException | InvalidJobParametersException e) {
      throw new IllegalStateException(
          "The retention run for " + businessDate + " could not start", e);
    }
  }
}
