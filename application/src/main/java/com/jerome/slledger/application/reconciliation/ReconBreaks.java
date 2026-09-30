package com.jerome.slledger.application.reconciliation;

import java.time.Instant;
import java.util.List;

/**
 * Where breaks are recorded. Idempotent by (snapshot, position): running a snapshot again adds
 * nothing.
 */
public interface ReconBreaks {

  /**
   * @param now when the breaks were found; a break for the settlement ladder has its first attempt
   *     due then
   * @return how many were new; the rest were already recorded. In the caller's transaction.
   */
  int save(List<ReconBreak> breaks, Instant now);
}
