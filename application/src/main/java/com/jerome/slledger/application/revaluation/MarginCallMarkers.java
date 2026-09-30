package com.jerome.slledger.application.revaluation;

/** Remembers which positions have raised their margin call at which price version. */
public interface MarginCallMarkers {

  /**
   * @return {@code true} if this is the first mark for (position, version), {@code false} if the
   *     call was already raised. In the caller's transaction.
   */
  boolean mark(String positionId, long priceVersion);
}
