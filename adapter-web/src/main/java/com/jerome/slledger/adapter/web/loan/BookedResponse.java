package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.loan.TradeDirection;
import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;

/**
 * What an open or a return answers: the position and the trade booked for it.
 *
 * <p>Built only from the trade, never from the position's current state, so that a replay of the
 * same submission answers byte for byte what the first call answered.
 */
public record BookedResponse(String positionId, TradeView trade) {

  public record TradeView(
      TradeDirection direction,
      long quantity,
      Money unitPrice,
      LocalDate tradeDate,
      LocalDate settlementDate) {}

  static BookedResponse of(LoanTrade trade) {
    return new BookedResponse(
        trade.position().value(),
        new TradeView(
            trade.direction(),
            trade.quantity(),
            trade.unitPrice(),
            trade.tradeDate(),
            trade.settlementDate()));
  }
}
