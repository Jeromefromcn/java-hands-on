package com.jerome.slledger.batch.support;

/**
 * A place a test can make a chunk fail, as the saga has one in stage 10. The default does nothing;
 * a test supplies a bean that throws at a chosen chunk, or stops a process there. It exists so a
 * failure can be placed <em>between</em> the reader having advanced and the chunk having committed,
 * which is where a restart's guarantees are decided and where no external fault can be aimed
 * precisely.
 */
public interface FailurePoint {

  /**
   * Called by a writer before it writes a chunk.
   *
   * @param step the step execution's name
   * @param firstKey the key of the first item in the chunk
   */
  void beforeWrite(String step, String firstKey);

  FailurePoint NONE = (step, firstKey) -> {};
}
