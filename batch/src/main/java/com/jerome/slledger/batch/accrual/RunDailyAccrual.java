package com.jerome.slledger.batch.accrual;

import com.jerome.slledger.application.batch.FencedRun;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

/**
 * The day-end accrual as the cutover runs it: on one instance only.
 *
 * <p>The instance that takes the lock for {@code accrual:<date>} launches the job with the lease's
 * fencing token as a <em>non-identifying</em> parameter. It is recorded with the execution, so
 * which owner ran what can be read from the repository, and it does not make a different job
 * instance: a date that failed under one owner is resumed under the next with the next owner's
 * token.
 *
 * <p>If the repository says an execution of the date is still running, and this instance holds the
 * lock, that execution's owner is dead or has lost its lease (a live owner holds the lock). It is
 * marked failed and the date is resumed from its saved cursor. A paused owner that wakes up is
 * refused at its next chunk by the fence.
 */
public final class RunDailyAccrual {

  private final RunAccrual accrual;
  private final FencedRun fenced;

  public RunDailyAccrual(RunAccrual accrual, FencedRun fenced) {
    this.accrual = accrual;
    this.fenced = fenced;
  }

  public RunAccrual.Outcome run(LocalDate businessDate) {
    Optional<RunAccrual.Outcome> outcome =
        fenced.launch(
            resource(businessDate),
            lease -> {
              JobParametersBuilder token =
                  new JobParametersBuilder().addLong("fencingToken", lease.fencingToken(), false);
              RunAccrual.Outcome first = accrual.run(businessDate, OptionalLong.empty(), token);
              if (first.status() == RunAccrual.Status.ALREADY_RUNNING
                  && accrual.recoverIfStale(businessDate, OptionalLong.empty())) {
                return accrual.run(businessDate, OptionalLong.empty(), token);
              }
              return first;
            });
    return outcome.orElseGet(() -> new RunAccrual.Outcome(RunAccrual.Status.NOT_OWNER, null));
  }

  /** The lock and fence resource for a date. */
  public static String resource(LocalDate businessDate) {
    return "accrual:" + businessDate;
  }
}
