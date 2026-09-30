package com.jerome.slledger.batch.accrual;

import java.time.LocalDate;
import java.util.OptionalLong;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;

/**
 * Launches the accrual for one business date.
 *
 * <p>The date is an <em>identifying</em> parameter, so the job instance is (job, date): launching a
 * date that completed is answered {@link Status#ALREADY_DONE}, not an error and not a second run,
 * and launching a date whose last execution failed resumes that instance from its saved cursor.
 *
 * <p>{@code rerun} is the operator's override: an extra identifying number that makes a different
 * instance of the same date. It exists to recompute a date on purpose; the entries already booked
 * are left alone by the store's primary key and the run reports them as already present.
 */
public final class RunAccrual {

  public enum Status {
    COMPLETED,
    FAILED,
    /** The instance for this date already completed; nothing was started. */
    ALREADY_DONE,
    /** An execution of this instance is running now, here or in another process. */
    ALREADY_RUNNING,
    /** Another instance holds the lock for this date; nothing was started (see RunDailyAccrual). */
    NOT_OWNER
  }

  public record Outcome(Status status, JobExecution execution) {}

  private final JobOperator operator;
  private final Job job;
  private final JobRepository repository;

  public RunAccrual(JobOperator operator, Job job, JobRepository repository) {
    this.operator = operator;
    this.job = job;
    this.repository = repository;
  }

  /**
   * Marks the last execution of the date's instance failed if the repository still says it is
   * running. Whether that execution is really dead is not known here: the caller has to have
   * established it (the lock holder can, because a live run holds the lock) and a wrong answer is
   * two live runs of one date, which the write fence exists to make harmless.
   *
   * @return whether an execution was recovered
   */
  public boolean recoverIfStale(LocalDate businessDate, OptionalLong rerun) {
    JobParametersBuilder identifying = new JobParametersBuilder();
    identifying.addLocalDate("businessDate", businessDate);
    rerun.ifPresent(n -> identifying.addLong("rerun", n));
    JobExecution last =
        repository.getLastJobExecution(job.getName(), identifying.toJobParameters());
    if (last == null || !last.isRunning()) {
      return false;
    }
    operator.recover(last);
    return true;
  }

  public Outcome run(LocalDate businessDate) {
    return run(businessDate, OptionalLong.empty(), new JobParametersBuilder());
  }

  public Outcome run(LocalDate businessDate, OptionalLong rerun, JobParametersBuilder extra) {
    JobParametersBuilder builder = new JobParametersBuilder(extra.toJobParameters());
    builder.addLocalDate("businessDate", businessDate);
    rerun.ifPresent(n -> builder.addLong("rerun", n));
    JobParameters parameters = builder.toJobParameters();
    try {
      JobExecution execution = operator.run(job, parameters);
      return new Outcome(
          execution.getStatus().name().equals("COMPLETED") ? Status.COMPLETED : Status.FAILED,
          execution);
    } catch (JobInstanceAlreadyCompleteException e) {
      return new Outcome(Status.ALREADY_DONE, null);
    } catch (JobExecutionAlreadyRunningException e) {
      return new Outcome(Status.ALREADY_RUNNING, null);
    } catch (org.springframework.batch.core.launch.JobRestartException
        | org.springframework.batch.core.job.parameters.InvalidJobParametersException e) {
      throw new IllegalStateException(
          "The accrual for " + businessDate + " could not be launched", e);
    }
  }
}
