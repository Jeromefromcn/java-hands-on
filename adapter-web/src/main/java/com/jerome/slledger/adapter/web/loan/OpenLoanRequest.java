package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;

/** The body of {@code POST /loans}. */
public record OpenLoanRequest(
    String counterpartyId,
    String isin,
    Long quantity,
    Money collateral,
    RuleRequest rule,
    LocalDate tradeDate) {

  /**
   * The accrual rule. Only {@code FLAT} is accepted: it is the only rule the ledger persists (stage
   * 6), so offering another here would be accepting a request that cannot be booked.
   */
  public record RuleRequest(RuleType type, Rate rate) {}

  public enum RuleType {
    FLAT
  }

  OpenPositionCommand toCommand(String idempotencyKey) {
    return new OpenPositionCommand(
        new IdempotencyKey(idempotencyKey),
        new CounterpartyId(counterpartyId),
        new Isin(isin),
        quantity,
        collateral,
        new FlatRate(rule.rate()),
        tradeDate);
  }
}
