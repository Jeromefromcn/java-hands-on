package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;

/**
 * How an accrual rule is stored: a type discriminator and the one parameter a flat rate has.
 *
 * <p>Only {@link FlatRate} is supported. {@code FloatingRate} holds a {@code ReferenceRates}
 * service, which is not a value and cannot be written to a column as it stands; {@code
 * MarginalTieredRate} needs a child table of tiers. Both are refused by name instead of being
 * written in a shape nothing can read back.
 */
public final class AccrualRuleColumns {

  static final String FLAT = "FLAT";

  private AccrualRuleColumns() {}

  static String typeOf(AccrualRule rule) {
    if (rule instanceof FlatRate) {
      return FLAT;
    }
    throw new UnsupportedAccrualRuleException(
        "Cannot store accrual rule %s: only FlatRate has a table in stage 6"
            .formatted(rule.getClass().getSimpleName()));
  }

  static BigDecimal flatRateOf(AccrualRule rule) {
    typeOf(rule);
    return ((FlatRate) rule).rate().annual();
  }

  public static AccrualRule toRule(String type, BigDecimal flatRate) {
    if (FLAT.equals(type)) {
      return new FlatRate(new Rate(flatRate));
    }
    throw new UnsupportedAccrualRuleException("Unknown stored accrual rule type " + type);
  }
}
