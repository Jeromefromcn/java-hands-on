package com.jerome.slledger.application.reconciliation;

import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/** One row of the custodian's snapshot, after it has been checked. */
public record CustodianHolding(String positionId, long quantity, Money value) {

  public CustodianHolding {
    Objects.requireNonNull(positionId, "positionId");
    Objects.requireNonNull(value, "value");
  }

  /**
   * Reads one line of {@code position_id,quantity,market_value,currency}, or refuses it.
   *
   * <p>The value crosses in from a file the ledger does not own, so it is held to the money
   * invariant here: an amount with more decimals than its currency has is refused, not rounded to
   * fit (rounding it would put a number in the comparison that the custodian never sent), and a
   * missing, unknown or pseudo currency is refused. A refused line is one row's problem: it is
   * skipped and recorded, and the position it names is left out of the comparison instead of being
   * reported as missing from the custodian.
   *
   * @throws MalformedHoldingException the line is not a valid holding
   */
  public static CustodianHolding parse(int line, String text) {
    String[] fields = text.split(",", -1);
    String id = fields.length > 0 && !fields[0].isBlank() ? fields[0].strip() : null;
    if (fields.length != 4) {
      throw new MalformedHoldingException(
          id, line, "expected 4 fields, found %d".formatted(fields.length));
    }
    if (id == null) {
      throw new MalformedHoldingException(null, line, "the position id is empty");
    }
    long quantity;
    try {
      quantity = Long.parseLong(fields[1].strip());
    } catch (NumberFormatException e) {
      throw new MalformedHoldingException(
          id, line, "quantity '%s' is not a whole number".formatted(fields[1]));
    }
    if (quantity <= 0) {
      throw new MalformedHoldingException(id, line, "quantity must be positive, was " + quantity);
    }
    BigDecimal amount;
    try {
      amount = new BigDecimal(fields[2].strip());
    } catch (NumberFormatException e) {
      throw new MalformedHoldingException(
          id, line, "value '%s' is not a number".formatted(fields[2]));
    }
    if (amount.signum() < 0) {
      throw new MalformedHoldingException(id, line, "value must not be negative");
    }
    String code = fields[3].strip();
    if (code.isEmpty()) {
      throw new MalformedHoldingException(id, line, "the currency is missing");
    }
    Currency currency;
    try {
      currency = Currency.getInstance(code);
    } catch (IllegalArgumentException e) {
      throw new MalformedHoldingException(id, line, "unknown currency '%s'".formatted(code));
    }
    if (currency.getDefaultFractionDigits() < 0) {
      throw new MalformedHoldingException(
          id, line, "'%s' is not a currency amounts are held in".formatted(code));
    }
    if (amount.scale() > currency.getDefaultFractionDigits()) {
      throw new MalformedHoldingException(
          id,
          line,
          "value %s has %d decimals, %s allows %d"
              .formatted(
                  amount.toPlainString(),
                  amount.scale(),
                  code,
                  currency.getDefaultFractionDigits()));
    }
    return new CustodianHolding(id, quantity, Money.of(amount, currency));
  }
}
