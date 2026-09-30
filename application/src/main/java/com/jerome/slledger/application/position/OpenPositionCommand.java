package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.RequestFingerprint;
import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;
import java.util.Objects;

/** A request to lend {@code quantity} of {@code security} to {@code counterparty}. */
public record OpenPositionCommand(
    IdempotencyKey key,
    CounterpartyId counterparty,
    Isin security,
    long quantity,
    Money collateral,
    AccrualRule rule,
    LocalDate tradeDate) {

  public OpenPositionCommand {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(counterparty, "counterparty");
    Objects.requireNonNull(security, "security");
    Objects.requireNonNull(collateral, "collateral");
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(tradeDate, "tradeDate");
  }

  /**
   * Every field but the key, in a fixed order. Amounts are compared as {@code Money} holds them, at
   * their currency's scale; rates keep the scale they were given, as {@code Rate} does.
   */
  public RequestFingerprint fingerprint() {
    return RequestFingerprint.of(
        String.join(
            "|",
            "OPEN",
            counterparty.value(),
            security.value(),
            Long.toString(quantity),
            collateral.amount().toPlainString(),
            collateral.currency().getCurrencyCode(),
            AccrualRules.canonical(rule),
            tradeDate.toString()));
  }
}
