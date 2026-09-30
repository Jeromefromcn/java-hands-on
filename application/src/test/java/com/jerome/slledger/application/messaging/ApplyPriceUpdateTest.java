package com.jerome.slledger.application.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.event.DomainEvents;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplyPriceUpdateTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Isin ISIN = new Isin("HK0000000007");
  // 18:30 UTC on 30 September is 02:30 on 1 October in Hong Kong.
  private static final Instant NOW = Instant.parse("2026-09-30T18:30:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final List<IntegrationEvent> outboxed = new ArrayList<>();
  private final List<Object> published = new ArrayList<>();
  private final List<LoanPosition> positions = new ArrayList<>();
  private boolean storeAccepts = true;

  private final SecurityPrices prices = (security, price, version) -> storeAccepts;
  private final SecurityPositions positionsOf = security -> List.copyOf(positions);
  private final Outbox outbox = outboxed::add;
  private final DomainEvents events = published::add;

  private ApplyPriceUpdate apply() {
    return new ApplyPriceUpdate(prices, positionsOf, outbox, events, CLOCK);
  }

  private static LoanPosition position(
      String id, long quantity, String collateral, Currency currency) {
    return LoanPosition.open(
        new LoanPositionId(id),
        new CounterpartyId("CP-001"),
        ISIN,
        quantity,
        Money.of(collateral, currency),
        new FlatRate(Rate.of("0.0365")));
  }

  private static PriceUpdated update(String price, Currency currency, long version) {
    return new PriceUpdated(UUID.randomUUID(), NOW, ISIN, Money.of(price, currency), version);
  }

  @Test
  @DisplayName(
      "a price that leaves collateral short raises one call, dated by the Hong Kong business day")
  void shortfallRaisesACall() {
    positions.add(position("P-1", 1_000, "50000.00", HKD));

    PriceApplication result = apply().apply(update("60.00", HKD, 3));

    assertThat(result).isEqualTo(new PriceApplication(true, 1, 0));
    assertThat(outboxed).singleElement().isInstanceOf(MarginCallRaised.class);
    MarginCallRaised call = (MarginCallRaised) outboxed.getFirst();
    assertThat(call.position()).isEqualTo(new LoanPositionId("P-1"));
    assertThat(call.counterparty()).isEqualTo(new CounterpartyId("CP-001"));
    assertThat(call.shortfall()).isEqualTo(Money.of("10000.00", HKD));
    assertThat(call.calledOn()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(call.occurredAt()).isEqualTo(NOW);
    assertThat(published).containsExactly(new PriceApplied(ISIN, Money.of("60.00", HKD), 3));
  }

  @Test
  @DisplayName(
      "collateral that still covers the position raises nothing, and the price is still applied")
  void coveredRaisesNothing() {
    positions.add(position("P-1", 1_000, "50000.00", HKD));

    PriceApplication result = apply().apply(update("41.30", HKD, 1));

    assertThat(result).isEqualTo(new PriceApplication(true, 0, 0));
    assertThat(outboxed).isEmpty();
    assertThat(published).hasSize(1);
  }

  @Test
  @DisplayName("a duplicate or stale update changes nothing: no call, no event")
  void ignoredUpdateIsSilent() {
    positions.add(position("P-1", 1_000, "50000.00", HKD));
    storeAccepts = false;

    PriceApplication result = apply().apply(update("60.00", HKD, 3));

    assertThat(result.applied()).isFalse();
    assertThat(outboxed).isEmpty();
    assertThat(published).isEmpty();
  }

  @Test
  @DisplayName(
      "a position whose collateral is in another currency is skipped and counted, the rest are valued")
  void unvaluablePositionDoesNotBlockThePrice() {
    positions.add(position("P-USD", 1_000, "50000.00", USD));
    positions.add(position("P-HKD", 1_000, "50000.00", HKD));

    PriceApplication result = apply().apply(update("60.00", HKD, 3));

    assertThat(result).isEqualTo(new PriceApplication(true, 1, 1));
    assertThat(outboxed)
        .extracting(event -> ((MarginCallRaised) event).position().value())
        .containsExactly("P-HKD");
    assertThat(published).hasSize(1);
  }

  @Test
  @DisplayName("the store's refusal propagates, and nothing was recorded")
  void refusalPropagates() {
    SecurityPrices refusing =
        (security, price, version) -> {
          throw new IllegalStateException("unknown security");
        };

    assertThatThrownBy(
            () ->
                new ApplyPriceUpdate(refusing, positionsOf, outbox, events, CLOCK)
                    .apply(update("1.00", HKD, 1)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(outboxed).isEmpty();
    assertThat(published).isEmpty();
  }
}
