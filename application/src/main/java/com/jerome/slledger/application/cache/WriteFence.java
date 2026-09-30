package com.jerome.slledger.application.cache;

/**
 * The check that makes a {@link DistributedLock} safe: the store a protected write lands in refuses
 * a write whose token is lower than one it has already seen.
 *
 * <p>Implementations take part in the caller's transaction, so a refused write and the write it
 * guarded roll back together.
 */
public interface WriteFence {

  /**
   * @throws StaleFencingTokenException a later token has already been admitted for the resource
   */
  void admit(String resource, long token);
}
