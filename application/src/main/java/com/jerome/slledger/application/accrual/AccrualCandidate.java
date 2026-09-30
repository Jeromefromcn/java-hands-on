package com.jerome.slledger.application.accrual;

import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.money.Money;

/**
 * One active position and the price to value it at, as a reader hands it to the processor.
 *
 * <p>A stored row that cannot be turned into a position is a {@link Corrupt} candidate, not an
 * exception from the read. A reader that threw would fail the whole page it was reading, and a
 * framework that skips a failed read skips the page; one bad row would take a hundred good ones
 * with it. As a value it reaches the processor, which refuses it alone.
 */
public sealed interface AccrualCandidate {

  String positionId();

  record Valid(LoanPosition position, Money unitPrice) implements AccrualCandidate {

    @Override
    public String positionId() {
      return position.id().value();
    }
  }

  record Corrupt(String positionId, String reason) implements AccrualCandidate {}
}
