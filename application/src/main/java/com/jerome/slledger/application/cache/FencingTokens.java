package com.jerome.slledger.application.cache;

/**
 * The source of fencing tokens: a counter that never goes backwards, kept by the same store that
 * later checks the tokens ({@link WriteFence}).
 *
 * <p>A counter held by the lock service cannot promise that. If the lock service restarts empty,
 * its counter starts again below a token the store has already admitted, and every later write is
 * refused. Issuing the token where it is checked removes that failure; the lock service keeps only
 * the coarse mutual exclusion.
 */
public interface FencingTokens {

  /** A value strictly greater than every value returned before, across all callers. */
  long next();
}
