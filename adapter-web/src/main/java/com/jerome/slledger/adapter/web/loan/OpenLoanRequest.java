package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.adapter.web.validation.NotNegative;
import com.jerome.slledger.adapter.web.validation.RateRange;
import com.jerome.slledger.adapter.web.validation.ValidIsin;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/** The body of {@code POST /loans}. */
public record OpenLoanRequest(
    @NotBlank String counterpartyId,
    @NotNull @ValidIsin String isin,
    @NotNull @Positive Long quantity,
    @NotNull @NotNegative Money collateral,
    @NotNull @Valid RuleRequest rule,
    @NotNull LocalDate tradeDate) {

  /**
   * The accrual rule. Only {@code FLAT} is accepted: it is the only rule the ledger persists (stage
   * 6), so offering another here would be accepting a request that cannot be booked.
   */
  public record RuleRequest(@NotNull RuleType type, @NotNull @RateRange Rate rate) {}

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
