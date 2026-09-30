package com.jerome.slledger.application.reconciliation;

import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;

/**
 * One position as the ledger and the custodian each see it, from a full outer join on the position
 * id: both sides, only ours, only theirs, or a side that could not be read.
 */
public sealed interface HoldingPair {

  String positionId();

  /**
   * What the ledger holds: outstanding quantity, its value at the security's price, and when it
   * settled.
   */
  record Ours(String positionId, long quantity, Money value, LocalDate settledOn) {}

  /** What the custodian's snapshot says. */
  record Theirs(String positionId, long quantity, Money value) {}

  record Both(Ours ours, Theirs theirs) implements HoldingPair {

    @Override
    public String positionId() {
      return ours.positionId();
    }
  }

  record OursOnly(Ours ours) implements HoldingPair {

    @Override
    public String positionId() {
      return ours.positionId();
    }
  }

  record TheirsOnly(Theirs theirs) implements HoldingPair {

    @Override
    public String positionId() {
      return theirs.positionId();
    }
  }

  /** The ledger's row could not be turned into a holding; there is nothing to compare. */
  record Unreadable(String positionId, String reason) implements HoldingPair {}
}
