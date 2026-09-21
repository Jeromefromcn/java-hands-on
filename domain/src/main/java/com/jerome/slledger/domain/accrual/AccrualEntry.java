package com.jerome.slledger.domain.accrual;

import com.jerome.slledger.domain.error.NegativeOutstandingValueException;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One day's fee, booked against one position.
 *
 * <p>This is the smallest auditable unit in the ledger, which is why the rounding policy is per
 * entry: an amount that has been booked cannot be recomputed at month end, and a total that cannot
 * be reproduced entry by entry is a total nobody can defend.
 */
public record AccrualEntry(LoanPositionId position, LocalDate accruedOn, Money fee) {

  public AccrualEntry {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(accruedOn, "accruedOn");
    Objects.requireNonNull(fee, "fee");
  }

  /**
   * Computes the day's fee and books it.
   *
   * <p>The {@code Money.of} below is the entire rounding policy of this domain: HALF_UP, to the
   * currency's scale, once, here. Everything upstream of it is unrounded; nothing downstream of it
   * rounds again. See {@code docs/decisions/02-domain-model.md}.
   */
  public static AccrualEntry accrue(
      LoanPositionId position, Money outstandingValue, AccrualRule rule, LocalDate on) {
    Objects.requireNonNull(outstandingValue, "outstandingValue");
    Objects.requireNonNull(rule, "rule");
    if (outstandingValue.isNegative()) {
      throw new NegativeOutstandingValueException(outstandingValue.amount());
    }

    BigDecimal unrounded = rule.dailyFee(outstandingValue.amount(), on);
    return new AccrualEntry(position, on, Money.of(unrounded, outstandingValue.currency()));
  }
}
