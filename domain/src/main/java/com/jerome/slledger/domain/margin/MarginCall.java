package com.jerome.slledger.domain.margin;

import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A demand for more collateral: on this date, this position was short by this much.
 *
 * <p>A shortfall of zero is refused rather than represented. A system that raises a call for
 * nothing teaches the people who receive calls to ignore them, and the absence of a call is already
 * expressible — {@code LoanPosition.revalue} returns an empty {@code Optional}.
 *
 * <p>A record of something that happened, so a record. What to do about it — notify, escalate,
 * liquidate — is not in this domain, and arrives with the event plumbing in stage 10.
 */
public record MarginCall(LoanPositionId position, LocalDate calledOn, Money shortfall) {

  public MarginCall {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(calledOn, "calledOn");
    Objects.requireNonNull(shortfall, "shortfall");
    if (shortfall.isNegative() || shortfall.equals(Money.zero(shortfall.currency()))) {
      throw new IllegalArgumentException(
          "A margin call needs a positive shortfall, was " + shortfall.amount().toPlainString());
    }
  }
}
