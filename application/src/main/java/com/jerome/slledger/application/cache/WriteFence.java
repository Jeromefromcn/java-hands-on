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

  /**
   * Checks that {@code token} is still the highest admitted for the resource, without raising it
   * and without excluding other writers that check: the lock it takes on the row is shared, so many
   * writers of one owner (the partitions of a batch) verify side by side, and an {@link #admit} by
   * a later owner waits for them to finish and then makes every later verify of the older token
   * fail.
   *
   * @throws StaleFencingTokenException a later token has been admitted, or none ever was
   */
  void verify(String resource, long token);
}
