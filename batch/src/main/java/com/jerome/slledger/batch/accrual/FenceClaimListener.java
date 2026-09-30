package com.jerome.slledger.batch.accrual;

import com.jerome.slledger.application.accrual.BookAccruals;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * Raises the write fence to this run's token before any step starts. A run whose token is already
 * behind another owner's fails here, before it reads a row; a run that gets past it holds the
 * highest token, and any older owner is refused at its next chunk.
 *
 * <p>A run without a token (launched without a lock) claims nothing and is not fenced.
 */
public final class FenceClaimListener implements JobExecutionListener {

  private final BookAccruals book;

  public FenceClaimListener(BookAccruals book) {
    this.book = book;
  }

  @Override
  public void beforeJob(JobExecution execution) {
    Long token = execution.getJobParameters().getLong("fencingToken");
    if (token != null) {
      var date = execution.getJobParameters().getLocalDate("businessDate");
      book.claim(new BookAccruals.Fenced(RunDailyAccrual.resource(date), token));
    }
  }
}
