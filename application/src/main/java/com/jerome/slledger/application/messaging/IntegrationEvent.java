package com.jerome.slledger.application.messaging;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A fact another system may need to hear about, as it crosses the process boundary.
 *
 * <p>Not {@code LoanEvent}: that is the domain's own vocabulary for what happened to a position,
 * and it is free to change. This is a published contract, with an id that identifies the message
 * end to end (the outbox row, the Kafka header, the consumer's dedup key) and a topic and key that
 * decide where it lands and what stays in order with what.
 *
 * <p>Every amount is a {@link Money}: a topic is a fourth boundary the money invariant has to hold
 * at, and the codec writes it as a string amount and a currency code.
 */
public sealed interface IntegrationEvent {

  /** Identifies this message everywhere it appears; a redelivery carries the same id. */
  UUID id();

  Instant occurredAt();

  String topic();

  /** What stays in order: events with the same key are consumed in the order they were sent. */
  String key();

  /** The name the codec writes into the {@code eventType} header and reads back. */
  String type();

  /**
   * A security's price changed, at a version the feed assigned. Keyed by ISIN, so two updates of
   * one security share a partition; the version is what makes an out-of-order arrival harmless
   * anyway.
   */
  record PriceUpdated(UUID id, Instant occurredAt, Isin security, Money price, long version)
      implements IntegrationEvent {

    public static final String TYPE = "PriceUpdated";

    public PriceUpdated {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(security, "security");
      Objects.requireNonNull(price, "price");
      if (version < 1) {
        throw new IllegalArgumentException("A price version starts at 1, was " + version);
      }
    }

    @Override
    public String topic() {
      return Topics.PRICE_UPDATES;
    }

    @Override
    public String key() {
      return security.value();
    }

    @Override
    public String type() {
      return TYPE;
    }
  }

  /** Revaluation found a position short of collateral. Keyed by position. */
  record MarginCallRaised(
      UUID id,
      Instant occurredAt,
      LoanPositionId position,
      CounterpartyId counterparty,
      Isin security,
      LocalDate calledOn,
      Money shortfall)
      implements IntegrationEvent {

    public static final String TYPE = "MarginCallRaised";

    public MarginCallRaised {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(counterparty, "counterparty");
      Objects.requireNonNull(security, "security");
      Objects.requireNonNull(calledOn, "calledOn");
      Objects.requireNonNull(shortfall, "shortfall");
    }

    @Override
    public String topic() {
      return Topics.MARGIN_CALLS;
    }

    @Override
    public String key() {
      return position.value();
    }

    @Override
    public String type() {
      return TYPE;
    }
  }

  /** A position was opened. Keyed by position. */
  record PositionOpened(
      UUID id,
      Instant occurredAt,
      LoanPositionId position,
      CounterpartyId counterparty,
      Isin security,
      long quantity,
      Money collateral)
      implements IntegrationEvent {

    public static final String TYPE = "PositionOpened";

    public PositionOpened {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(counterparty, "counterparty");
      Objects.requireNonNull(security, "security");
      Objects.requireNonNull(collateral, "collateral");
    }

    @Override
    public String topic() {
      return Topics.POSITION_EVENTS;
    }

    @Override
    public String key() {
      return position.value();
    }

    @Override
    public String type() {
      return TYPE;
    }
  }
}
