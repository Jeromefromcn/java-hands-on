package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.port.BookedTrade;
import com.jerome.slledger.application.port.RequestFingerprint;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.party.CounterpartyId;

public final class LoanTradeMapper {

  private LoanTradeMapper() {}

  public static LoanTradeEntity toEntity(LoanTrade trade, RequestFingerprint fingerprint) {
    LoanTradeEntity entity = new LoanTradeEntity();
    entity.requestFingerprint = fingerprint.sha256();
    entity.idempotencyKey = trade.idempotencyKey().value();
    entity.positionId = trade.position().value();
    entity.counterpartyId = trade.counterparty().value();
    entity.isin = trade.security().value();
    entity.direction = trade.direction();
    entity.quantity = trade.quantity();
    entity.unitPriceAmount = MoneyColumns.amountOf(trade.unitPrice());
    entity.unitPriceCurrency = MoneyColumns.currencyOf(trade.unitPrice());
    entity.tradeDate = trade.tradeDate();
    entity.settlementDate = trade.settlementDate();
    return entity;
  }

  public static BookedTrade toBooked(LoanTradeEntity entity) {
    return new BookedTrade(
        toDomain(entity),
        entity.requestFingerprint == null
            ? null
            : new RequestFingerprint(entity.requestFingerprint));
  }

  public static LoanTrade toDomain(LoanTradeEntity entity) {
    return LoanTrade.builder()
        .idempotencyKey(new IdempotencyKey(entity.idempotencyKey))
        .position(new LoanPositionId(entity.positionId))
        .counterparty(new CounterpartyId(entity.counterpartyId))
        .security(new Isin(entity.isin.trim()))
        .direction(entity.direction)
        .quantity(entity.quantity)
        .unitPrice(MoneyColumns.read(entity.unitPriceAmount, entity.unitPriceCurrency))
        .tradeDate(entity.tradeDate)
        .settlementDate(entity.settlementDate)
        .build();
  }
}
