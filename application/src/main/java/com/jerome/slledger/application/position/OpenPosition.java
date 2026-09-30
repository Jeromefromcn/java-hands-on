package com.jerome.slledger.application.position;

import com.jerome.slledger.application.messaging.IntegrationEvent.PositionOpened;
import com.jerome.slledger.application.messaging.Outbox;
import com.jerome.slledger.application.port.CounterpartyDirectory;
import com.jerome.slledger.application.port.DuplicateRequestException;
import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.application.port.TradeLedger;
import com.jerome.slledger.domain.calendar.HolidayCalendar;
import com.jerome.slledger.domain.calendar.SettlementDates;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import com.jerome.slledger.domain.loan.LoanPositions;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.loan.TradeDirection;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.Counterparty;
import com.jerome.slledger.domain.party.ExposureSummary;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens a position: credit check, the position itself, and its borrow trade, in one transaction.
 *
 * <p>The credit check reads the counterparty's active positions and lets {@code domain} total them,
 * so a counterparty with positions in two currencies is refused by {@code domain}'s currency rule
 * rather than summed. The check is not a lock: two opens for the same counterparty running at the
 * same moment can each pass against the exposure before the other. That race is stage 8's.
 */
public class OpenPosition {

  private final CounterpartyDirectory counterparties;
  private final SecurityDirectory securities;
  private final LoanPositionRepository positions;
  private final TradeLedger trades;
  private final HolidayCalendar calendar;
  private final Outbox outbox;
  private final Clock clock;

  public OpenPosition(
      CounterpartyDirectory counterparties,
      SecurityDirectory securities,
      LoanPositionRepository positions,
      TradeLedger trades,
      HolidayCalendar calendar) {
    this(counterparties, securities, positions, trades, calendar, Outbox.NONE, Clock.systemUTC());
  }

  /**
   * As above, and the open also records a {@code PositionOpened} in {@code outbox}, in the same
   * transaction as the position and its trade.
   */
  public OpenPosition(
      CounterpartyDirectory counterparties,
      SecurityDirectory securities,
      LoanPositionRepository positions,
      TradeLedger trades,
      HolidayCalendar calendar,
      Outbox outbox,
      Clock clock) {
    this.counterparties = counterparties;
    this.securities = securities;
    this.positions = positions;
    this.trades = trades;
    this.calendar = calendar;
    this.outbox = outbox;
    this.clock = clock;
  }

  /**
   * @return the borrow trade booked, which names the new position
   * @throws DuplicateRequestException if the command's key is already booked; this transaction has
   *     then rolled back, and {@link IdempotentBooking} decides what the caller is told
   */
  @Transactional
  public LoanTrade open(OpenPositionCommand command) {
    Counterparty counterparty =
        counterparties
            .find(command.counterparty())
            .orElseThrow(
                () ->
                    UnknownReferenceException.reference(
                        "counterparty", command.counterparty().value()));
    Security security =
        securities
            .find(command.security())
            .orElseThrow(
                () -> UnknownReferenceException.reference("security", command.security().value()));

    Money requested = command.collateral();
    ExposureSummary exposure =
        LoanPositions.exposureByCounterparty(
                positions.activeOf(counterparty.id()), requested.currency())
            .getOrDefault(
                counterparty.id(), new ExposureSummary(0, Money.zero(requested.currency())));
    counterparty.creditLimit().checkCanAbsorb(exposure.totalCollateral(), requested);

    LoanPosition position =
        LoanPosition.open(
            new LoanPositionId(UUID.randomUUID().toString()),
            counterparty.id(),
            security.isin(),
            command.quantity(),
            requested,
            command.rule());
    positions.add(position);

    LoanTrade borrow =
        LoanTrade.builder()
            .idempotencyKey(command.key())
            .position(position.id())
            .counterparty(counterparty.id())
            .security(security.isin())
            .direction(TradeDirection.BORROW)
            .quantity(command.quantity())
            .unitPrice(security.marketPrice())
            .tradeDate(command.tradeDate())
            .settlementDate(SettlementDates.settlementDate(command.tradeDate(), calendar))
            .build();
    trades.book(borrow, command.fingerprint());
    outbox.append(
        new PositionOpened(
            UUID.randomUUID(),
            clock.instant(),
            position.id(),
            counterparty.id(),
            security.isin(),
            command.quantity(),
            requested));
    return borrow;
  }
}
