package com.jerome.slledger.batch.support;

import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.dao.TransientDataAccessException;

/**
 * The fault handling every chunked job here shares, in one place so that it is one decision and not
 * one per job.
 *
 * <ul>
 *   <li><b>Skip</b> an item the job refuses for a reason that belongs to that item (the caller
 *       lists the exception types), up to {@code skipLimit} items in the step; the next one fails
 *       the step. A limit is what separates "a few bad rows" from "the input is wrong".
 *   <li><b>Retry</b> a chunk that fails with a transient data-access error (a lock timeout, a
 *       dropped connection), up to {@code retryLimit} attempts. Nothing else is retried: a
 *       permanent failure repeated is only slower.
 *   <li><b>Fail the step</b> for everything else: a stale fencing token, an interrupt, a bug. The
 *       step is then restartable from its saved cursor.
 * </ul>
 *
 * <p>Composition, not a base class: a job passes its builder through here and keeps its own reader,
 * processor and writer.
 */
public final class StepDefaults {

  private StepDefaults() {}

  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <I, O> ChunkOrientedStepBuilder<I, O> faultTolerant(
      ChunkOrientedStepBuilder<I, O> builder,
      int skipLimit,
      int retryLimit,
      Class<? extends Throwable>... skippable) {
    return builder
        .faultTolerant()
        .skip(skippable)
        .skipLimit(skipLimit)
        .retry(TransientDataAccessException.class)
        .retryLimit(retryLimit);
  }
}
