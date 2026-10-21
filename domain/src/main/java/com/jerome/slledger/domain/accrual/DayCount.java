package com.jerome.slledger.domain.accrual;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;

/**
 * How many days a year has, for turning an annual rate into a day's fee.
 *
 * <p>A convention, not a law. Until stage 14 the ledger had one, ACT/365 fixed, as a constant of
 * {@link AccrualRule}, because it booked HKD. Money markets in USD, EUR and CHF accrue on ACT/360,
 * which makes every daily figure 365/360 of the ACT/365F one, about 1.4 percent more; booking those
 * currencies on 365 under-charges every day. Which convention a position accrues on is decided
 * outside the domain (a rollout, in stage 14); the domain only knows each convention's divisor and
 * each currency's market convention.
 */
public enum DayCount {
  ACT_365F(new BigDecimal("365")),
  ACT_360(new BigDecimal("360"));

  private static final Set<String> ACT_360_MARKETS = Set.of("USD", "EUR", "CHF");

  private final BigDecimal basis;

  DayCount(BigDecimal basis) {
    this.basis = basis;
  }

  /** The divisor of the annual fee. */
  public BigDecimal basis() {
    return basis;
  }

  /**
   * The money-market convention of the currency: ACT/360 for USD, EUR and CHF, ACT/365F otherwise.
   * "Otherwise" is the ledger's convention before stage 14, so an unlisted currency keeps the
   * figures it has always had.
   */
  public static DayCount marketConvention(Currency currency) {
    return ACT_360_MARKETS.contains(currency.getCurrencyCode()) ? ACT_360 : ACT_365F;
  }
}
