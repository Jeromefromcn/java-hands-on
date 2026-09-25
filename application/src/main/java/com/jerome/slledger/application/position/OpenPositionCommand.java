package com.jerome.slledger.application.position;

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
}
