package com.jerome.slledger.application.reconciliation;

import java.util.List;

/**
 * Where breaks are recorded. Idempotent by (snapshot, position): running a snapshot again adds
 * nothing.
 */
public interface ReconBreaks {

  /**
   * @return how many were new; the rest were already recorded. In the caller's transaction.
   */
  int save(List<ReconBreak> breaks);
}
