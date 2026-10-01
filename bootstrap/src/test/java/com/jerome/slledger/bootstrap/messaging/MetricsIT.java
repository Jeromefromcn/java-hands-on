package com.jerome.slledger.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.PublishPriceUpdate;
import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.application.quote.QuotePrice;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The scrape of a running ledger after the business flow has happened: an open, a price that raises
 * a margin call, the notice, and a quote that could not be served. Every claim is about the text a
 * Prometheus would read.
 */
@Timeout(300)
class MetricsIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static MessagingLedger ledger;
  private static PrometheusMeterRegistry prometheus;

  @BeforeAll
  static void start() {
    ledger =
        MessagingLedger.start(
            "--ledger.messaging.relay-enabled=true",
            "--ledger.messaging.consumers-enabled=true",
            "--ledger.messaging.relay-interval=100ms",
            "--ledger.messaging.consumer-group-prefix=metrics-" + UUID.randomUUID(),
            "--ledger.marketdata.base-url=http://127.0.0.1:9",
            "--ledger.marketdata.connect-timeout=200ms");
    prometheus = ledger.app.getBean(PrometheusMeterRegistry.class);
  }

  @AfterAll
  static void stop() {
    ledger.close();
  }

  private static <T> T await(String what, Supplier<T> probe, Predicate<T> done) {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    T value = probe.get();
    while (!done.test(value)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "timed out waiting for " + what + "; last scrape:\n" + prometheus.scrape());
      }
      try {
        Thread.sleep(200); // real time: the relay and the consumers run on their own threads
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      value = probe.get();
    }
    return value;
  }

  @Test
  @DisplayName(
      "the business counters, the resilience and queue gauges are in the scrape, untagged by id")
  void scrape() {
    ledger
        .app
        .getBean(OpenPosition.class)
        .open(
            new OpenPositionCommand(
                new IdempotencyKey("METRICS-" + UUID.randomUUID()),
                new CounterpartyId("CP-001"),
                new Isin("HK0000000001"),
                1_000,
                Money.of("50000.00", HKD),
                new FlatRate(Rate.of("0.0365")),
                LocalDate.of(2026, 9, 1)));
    ledger
        .app
        .getBean(PublishPriceUpdate.class)
        .publish(
            new PriceUpdated(
                UUID.randomUUID(),
                Instant.now(),
                new Isin("HK0000000001"),
                Money.of("60.00", HKD),
                System.currentTimeMillis() /* newer than any version the shared topic holds */));
    try {
      ledger.app.getBean(QuotePrice.class).quote(new Isin("HK0000000001"));
    } catch (RuntimeException sourceIsDown) {
      // The source is a closed port: the failed quote is what is counted.
    }

    String text =
        await(
            "a notice sent for the margin call",
            prometheus::scrape,
            t ->
                t.contains("ledger_notices_sent_total")
                    && t.contains("ledger_margin_calls_raised_total"));
    Evidence.write("12-metrics-scrape.txt", text);

    assertThat(text)
        .contains("ledger_positions_opened_total{currency=\"HKD\"} 1.0")
        .contains("ledger_quote_requests_total{result=\"failed\"} 1.0")
        .contains("ledger_outbox_oldest_age_seconds")
        .contains("ledger_outbox_unpublished")
        .contains("ledger_marketdata_pool_active")
        .contains("resilience4j_circuitbreaker_state{")
        .contains("resilience4j_bulkhead_available_concurrent_calls{")
        .contains("jvm_memory_used_bytes")
        .contains("hikaricp_connections_active")
        .contains("kafka_consumer_fetch_manager_records_lag");

    // Cardinality: no ledger meter is labelled with an identifier.
    Set<String> forbidden = Set.of("counterparty", "position", "isin", "id", "key", "trade");
    List<String> labelled =
        text.lines()
            .filter(line -> line.startsWith("ledger_") && line.contains("{"))
            .filter(
                line -> {
                  String labels = line.substring(line.indexOf('{') + 1, line.indexOf('}'));
                  return java.util.Arrays.stream(labels.split(","))
                      .map(pair -> pair.substring(0, pair.indexOf('=')))
                      .anyMatch(forbidden::contains);
                })
            .toList();
    assertThat(labelled).as("ledger meters labelled with an identifier").isEmpty();
  }
}
