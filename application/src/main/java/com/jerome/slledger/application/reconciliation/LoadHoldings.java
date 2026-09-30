package com.jerome.slledger.application.reconciliation;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Loads a chunk of a custodian's snapshot in the chunk's transaction. */
public class LoadHoldings {

  private final CustodianHoldings holdings;

  public LoadHoldings(CustodianHoldings holdings) {
    this.holdings = holdings;
  }

  @Transactional
  public void load(String snapshot, List<CustodianHolding> chunk) {
    holdings.save(snapshot, chunk);
  }
}
