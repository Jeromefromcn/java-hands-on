package com.jerome.slledger.domain.loan;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One leg of a loan: a borrow or a return, as agreed on a trade date and settling later.
 *
 * <p>An immutable record of something that happened, so a record. The settlement date is stored
 * rather than recomputed on read, because the holiday calendar it was derived from can change and a
 * trade records what the two sides agreed, not what today's calendar would produce.
 *
 * <p>Nine components, four of them a {@code LocalDate} or a {@code long}. Transposing two of those
 * in the canonical constructor compiles, passes every type check, and books a trade dated its own
 * settlement date. That is what {@link Builder} exists for — see the test that builds the same
 * trade both ways.
 */
public record LoanTrade(
    IdempotencyKey idempotencyKey,
    LoanPositionId position,
    CounterpartyId counterparty,
    Isin security,
    TradeDirection direction,
    long quantity,
    Money unitPrice,
    LocalDate tradeDate,
    LocalDate settlementDate) {

  public LoanTrade {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(counterparty, "counterparty");
    Objects.requireNonNull(security, "security");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(unitPrice, "unitPrice");
    Objects.requireNonNull(tradeDate, "tradeDate");
    Objects.requireNonNull(settlementDate, "settlementDate");
    if (quantity <= 0) {
      throw new IllegalArgumentException("Quantity must be positive, was " + quantity);
    }
    if (settlementDate.isBefore(tradeDate)) {
      throw new IllegalArgumentException(
          "A settlement date cannot precede its trade date: %s settles %s"
              .formatted(tradeDate, settlementDate));
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Names the nine components at the call site.
   *
   * <p>What it buys over the canonical constructor is not fewer keystrokes — it is more. It buys a
   * compile error becoming a readable line, and it buys {@code build()} as a single place where a
   * half-specified trade is refused by the name of the missing part. The canonical constructor
   * cannot express that: a missing argument there is a compile error and a null one is not.
   */
  public static final class Builder {

    private IdempotencyKey idempotencyKey;
    private LoanPositionId position;
    private CounterpartyId counterparty;
    private Isin security;
    private TradeDirection direction;
    private long quantity;
    private Money unitPrice;
    private LocalDate tradeDate;
    private LocalDate settlementDate;

    private Builder() {}

    public Builder idempotencyKey(IdempotencyKey idempotencyKey) {
      this.idempotencyKey = idempotencyKey;
      return this;
    }

    public Builder position(LoanPositionId position) {
      this.position = position;
      return this;
    }

    public Builder counterparty(CounterpartyId counterparty) {
      this.counterparty = counterparty;
      return this;
    }

    public Builder security(Isin security) {
      this.security = security;
      return this;
    }

    public Builder direction(TradeDirection direction) {
      this.direction = direction;
      return this;
    }

    public Builder quantity(long quantity) {
      this.quantity = quantity;
      return this;
    }

    public Builder unitPrice(Money unitPrice) {
      this.unitPrice = unitPrice;
      return this;
    }

    public Builder tradeDate(LocalDate tradeDate) {
      this.tradeDate = tradeDate;
      return this;
    }

    public Builder settlementDate(LocalDate settlementDate) {
      this.settlementDate = settlementDate;
      return this;
    }

    public LoanTrade build() {
      return new LoanTrade(
          idempotencyKey,
          position,
          counterparty,
          security,
          direction,
          quantity,
          unitPrice,
          tradeDate,
          settlementDate);
    }
  }
}
