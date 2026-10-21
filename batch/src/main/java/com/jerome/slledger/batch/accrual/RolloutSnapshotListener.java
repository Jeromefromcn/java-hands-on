package com.jerome.slledger.batch.accrual;

import com.jerome.slledger.application.accrual.DayCountRollout;
import com.jerome.slledger.application.rollout.Rollout;
import com.jerome.slledger.application.rollout.RolloutStore;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * Takes the day-count rollout's percentage once, when the run starts, into the job's execution
 * context, where the processor reads it. A restart of a failed run carries the context of the
 * execution it resumes, so the percentage found there is kept: a rollout raised or killed between
 * the failure and the restart does not split one business date between two rules. The next date's
 * run takes the new value. See docs/decisions/14-delivery.md.
 */
public final class RolloutSnapshotListener implements JobExecutionListener {

  public static final String KEY = "accrual.dayCountRolloutPercent";

  private final RolloutStore rollouts;

  public RolloutSnapshotListener(RolloutStore rollouts) {
    this.rollouts = rollouts;
  }

  @Override
  public void beforeJob(JobExecution execution) {
    var context = execution.getExecutionContext();
    if (!context.containsKey(KEY)) {
      context.putInt(KEY, rollouts.find(DayCountRollout.FLAG).map(Rollout::percent).orElse(0));
    }
  }
}
