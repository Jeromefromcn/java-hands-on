package com.jerome.slledger.adapter.persistence.support;

import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.util.Currency;

/**
 * The one way a stored amount becomes {@link Money}.
 *
 * <p>{@code Money}'s constructor re-scales to the currency's minor units, and re-scaling is a
 * rounding. A column holding {@code HKD 143.845} would come back as {@code 143.85}: a second
 * rounding step outside {@code domain}, where the stage-2 rule says rounding happens exactly once.
 * So the amount is compared with what was stored, and a difference is refused rather than absorbed.
 * Stage 5 found this the hard way in one reader; this class is so no reader has to.
 */
public final class MoneyColumns {

  private MoneyColumns() {}

  public static Money read(BigDecimal stored, String currencyCode) {
    // char(3) comes back blank-padded when the driver reports it as bpchar.
    String code = currencyCode.trim();
    Money money = Money.of(stored, Currency.getInstance(code));
    if (stored.compareTo(money.amount()) != 0) {
      throw new ArithmeticException(
          "Stored amount %s %s has more precision than %s allows; refusing to round it again"
              .formatted(stored.toPlainString(), code, code));
    }
    return money;
  }

  public static BigDecimal amountOf(Money money) {
    return money.amount();
  }

  public static String currencyOf(Money money) {
    return money.currency().getCurrencyCode();
  }
}
