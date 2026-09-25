package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.application.port.TradeLedger;
import com.jerome.slledger.domain.calendar.HolidayCalendar;
import com.jerome.slledger.domain.calendar.SettlementDates;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.loan.TradeDirection;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at a return, in one transaction: load, apply, write back, book.
 *
 * <p>A separate bean from {@link ReturnPosition}, not a method on it, for the reason stage 5 found:
 * the retry loop must call this through its proxy, and a call from inside the same class would
 * bypass the proxy and with it the transaction boundary the retry depends on.
 */
public class ReturnPositionOnce {

  private final LoanPositionRepository positions;
  private final SecurityDirectory securities;
  private final TradeLedger trades;
  private final HolidayCalendar calendar;

  public ReturnPositionOnce(
      LoanPositionRepository positions,
      SecurityDirectory securities,
      TradeLedger trades,
      HolidayCalendar calendar) {
    this.positions = positions;
    this.securities = securities;
    this.trades = trades;
    this.calendar = calendar;
  }

  @Transactional
  public void apply(ReturnCommand command) {
    LoanPosition position =
        positions
            .find(command.position())
            .orElseThrow(
                () -> new UnknownReferenceException("position", command.position().value()));
    position.returnQuantity(command.quantity());
    positions.add(position);

    Security security =
        securities
            .find(position.security())
            .orElseThrow(
                () -> new UnknownReferenceException("security", position.security().value()));
    trades.book(
        LoanTrade.builder()
            .idempotencyKey(command.key())
            .position(position.id())
            .counterparty(position.counterparty())
            .security(position.security())
            .direction(TradeDirection.RETURN)
            .quantity(command.quantity())
            .unitPrice(security.marketPrice())
            .tradeDate(command.tradeDate())
            .settlementDate(SettlementDates.settlementDate(command.tradeDate(), calendar))
            .build());
  }
}
