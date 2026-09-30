package com.jerome.slledger.application.reconciliation;

import java.util.List;

/** The custodian's snapshot as loaded, one row per (snapshot, position). */
public interface CustodianHoldings {

  /**
   * Inserts or replaces, so a restarted load that reads a line again leaves one row. In the
   * caller's transaction.
   */
  void save(String snapshot, List<CustodianHolding> holdings);
}
