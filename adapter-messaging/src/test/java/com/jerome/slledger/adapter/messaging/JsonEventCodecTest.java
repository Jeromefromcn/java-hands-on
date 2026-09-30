package com.jerome.slledger.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.messaging.IntegrationEvent;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PositionOpened;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.MessageDecodeException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JsonEventCodecTest {

  private static final Instant AT = Instant.parse("2026-09-30T08:15:30.123456Z");
  private static final Isin ISIN = new Isin("HK0000000007");

  private final JsonEventCodec codec = new JsonEventCodec();

  private static Money money(String amount, String currency) {
    return Money.of(amount, Currency.getInstance(currency));
  }

  @ParameterizedTest(name = "{0} {1} survives every event type")
  @CsvSource({
    "1234567890123456.78,HKD",
    "1234567890123456,JPY",
    "1234567890123456.789,BHD",
    "0.01,HKD",
  })
  @DisplayName("money round-trips at 16 integer digits in a 2, 0 and 3 decimal currency")
  void moneyRoundTrips(String amount, String currency) {
    Money money = money(amount, currency);
    IntegrationEvent[] events = {
      new PriceUpdated(UUID.randomUUID(), AT, ISIN, money, 7),
      new MarginCallRaised(
          UUID.randomUUID(),
          AT,
          new LoanPositionId("P-1"),
          new CounterpartyId("CP-001"),
          ISIN,
          LocalDate.of(2026, 9, 30),
          money),
      new PositionOpened(
          UUID.randomUUID(),
          AT,
          new LoanPositionId("P-1"),
          new CounterpartyId("CP-001"),
          ISIN,
          1000,
          money)
    };

    for (IntegrationEvent event : events) {
      String payload = codec.encode(event);
      IntegrationEvent decoded = codec.decode(event.type(), payload);

      assertThat(decoded).isEqualTo(event);
      // The amount is on the wire as a string, at the currency's scale, never a JSON number.
      assertThat(payload).contains("\"amount\":\"" + money.amount().toPlainString() + "\"");
    }
  }

  @Test
  @DisplayName("routing follows the spec: ISIN for prices, position id for the rest")
  void keysAndTopics() {
    PriceUpdated price = new PriceUpdated(UUID.randomUUID(), AT, ISIN, money("1.00", "HKD"), 1);
    MarginCallRaised call =
        new MarginCallRaised(
            UUID.randomUUID(),
            AT,
            new LoanPositionId("P-9"),
            new CounterpartyId("CP-001"),
            ISIN,
            LocalDate.of(2026, 9, 30),
            money("1.00", "HKD"));

    assertThat(price.key()).isEqualTo("HK0000000007");
    assertThat(price.topic()).isEqualTo("sl.price-updates");
    assertThat(call.key()).isEqualTo("P-9");
    assertThat(call.topic()).isEqualTo("sl.margin-calls");
  }

  private static String price(String priceJson) {
    return """
        {"eventId":"%s","occurredAt":"2026-09-30T08:15:30Z","isin":"HK0000000007",
         "price":%s,"version":1}"""
        .formatted(UUID.randomUUID(), priceJson);
  }

  @Test
  @DisplayName("an amount written as a JSON number is refused")
  void numberAmountIsRefused() {
    assertThatThrownBy(
            () -> codec.decode("PriceUpdated", price("{\"amount\":41.30,\"currency\":\"HKD\"}")))
        .isInstanceOf(MessageDecodeException.class);
  }

  @Test
  @DisplayName("an amount with more decimals than its currency is refused, not rounded")
  void overPreciseIsRefused() {
    assertThatThrownBy(
            () ->
                codec.decode("PriceUpdated", price("{\"amount\":\"41.301\",\"currency\":\"HKD\"}")))
        .isInstanceOf(MessageDecodeException.class)
        .hasMessageContaining("more precision");
    assertThatThrownBy(
            () -> codec.decode("PriceUpdated", price("{\"amount\":\"41.5\",\"currency\":\"JPY\"}")))
        .isInstanceOf(MessageDecodeException.class);
  }

  @Test
  @DisplayName(
      "an unknown currency, a currency with no minor unit, and a malformed amount are refused")
  void badCurrencyAndAmount() {
    for (String bad :
        new String[] {
          "{\"amount\":\"1.00\",\"currency\":\"ZZZ\"}",
          "{\"amount\":\"1.00\",\"currency\":\"XXX\"}",
          "{\"amount\":\"1.00\",\"currency\":\"hkd\"}",
          "{\"amount\":\"1e2\",\"currency\":\"HKD\"}",
          "{\"amount\":\"1.00\"}",
          "{\"amount\":\"1.00\",\"currency\":\"HKD\",\"extra\":1}",
          "\"41.30 HKD\""
        }) {
      assertThatThrownBy(() -> codec.decode("PriceUpdated", price(bad)))
          .as(bad)
          .isInstanceOf(MessageDecodeException.class);
    }
  }

  @Test
  @DisplayName("an unknown field, a missing field, a wrong type and a bad version are refused")
  void structureIsStrict() {
    String id = UUID.randomUUID().toString();
    String[] payloads = {
      "{\"eventId\":\""
          + id
          + "\",\"occurredAt\":\"2026-09-30T08:15:30Z\",\"isin\":\"HK0000000007\","
          + "\"price\":{\"amount\":\"1.00\",\"currency\":\"HKD\"},\"version\":1,\"note\":\"x\"}",
      "{\"eventId\":\""
          + id
          + "\",\"occurredAt\":\"2026-09-30T08:15:30Z\",\"isin\":\"HK0000000007\","
          + "\"version\":1}",
      "{\"eventId\":\""
          + id
          + "\",\"occurredAt\":\"2026-09-30T08:15:30Z\",\"isin\":7,"
          + "\"price\":{\"amount\":\"1.00\",\"currency\":\"HKD\"},\"version\":1}",
      "{\"eventId\":\""
          + id
          + "\",\"occurredAt\":\"2026-09-30T08:15:30Z\",\"isin\":\"HK0000000007\","
          + "\"price\":{\"amount\":\"1.00\",\"currency\":\"HKD\"},\"version\":0}",
      "{\"eventId\":\""
          + id
          + "\",\"occurredAt\":\"2026-09-30T08:15:30Z\",\"isin\":\"HK0000000007\","
          + "\"price\":{\"amount\":\"1.00\",\"currency\":\"HKD\"},\"version\":1.5}",
      "{\"eventId\":\"not-a-uuid\",\"occurredAt\":\"2026-09-30T08:15:30Z\",\"isin\":\"HK0000000007\","
          + "\"price\":{\"amount\":\"1.00\",\"currency\":\"HKD\"},\"version\":1}",
      "{\"eventId\":\""
          + id
          + "\",\"occurredAt\":\"2026-09-30T08:15:30Z\",\"isin\":\"bad\","
          + "\"price\":{\"amount\":\"1.00\",\"currency\":\"HKD\"},\"version\":1}",
      "[1,2]",
      "not json"
    };
    for (String payload : payloads) {
      assertThatThrownBy(() -> codec.decode("PriceUpdated", payload))
          .as(payload)
          .isInstanceOf(MessageDecodeException.class);
    }
  }

  @Test
  @DisplayName("an unknown event type is refused")
  void unknownType() {
    assertThatThrownBy(() -> codec.decode("Surprise", "{}"))
        .isInstanceOf(MessageDecodeException.class)
        .hasMessageContaining("unknown event type");
  }
}
