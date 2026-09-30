package com.jerome.slledger.application.reconciliation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The ledger's holdings on a date joined to a loaded snapshot, in position-id order, by keyset. A
 * position whose line in the snapshot was refused as malformed is left out, so a bad line is not
 * mistaken for a position the custodian does not hold.
 */
public interface HoldingComparison {

  List<HoldingPair> page(
      String snapshot, LocalDate businessDate, Optional<String> afterId, int limit);
}
