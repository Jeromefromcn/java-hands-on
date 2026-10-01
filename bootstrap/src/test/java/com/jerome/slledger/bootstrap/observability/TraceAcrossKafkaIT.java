package com.jerome.slledger.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jerome.slledger.adapter.messaging.JsonEventCodec;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.NoticeChannel;
import com.jerome.slledger.application.messaging.OutboxStore;
import com.jerome.slledger.application.messaging.PublishPriceUpdate;
import com.jerome.slledger.application.messaging.StoredOutbox;
import com.jerome.slledger.application.messaging.Topics;
import com.jerome.slledger.application.observability.Tracing;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One trace across the boundaries nothing carries by itself. The relay and the consumers run on
 * their own threads, long after the request's span has ended; what links them is the {@code
 * traceparent} the outbox stores with the event and the Kafka header the sender puts on the record.
 */
@Timeout(300)
class TraceAcrossKafkaIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final String ISIN = "HK0000000007"; // valid check digits: the web layer checks
  private static final JsonEventCodec CODEC = new JsonEventCodec();

  static final List<NoticeChannel.MarginCallNotice> NOTICES = new CopyOnWriteArrayList<>();

  /** Test-only source: notices are recorded, not logged. */
  static class Recording {
    @Bean
    @Primary
    NoticeChannel recordingNoticeChannel() {
      return NOTICES::add;
    }
  }

  private static ConfigurableApplicationContext app;
  private static RestTestClient http;
  private static JdbcClient jdbc;
  private static Tracing tracing;

  @BeforeAll
  static void start() {
    SharedKafka.start();
    SharedKafka.ensureTopic(Topics.PRICE_UPDATES, 1);
    SharedKafka.ensureTopic(Topics.MARGIN_CALLS, 1);
    SharedKafka.ensureTopic(Topics.POSITION_EVENTS, 1);
    app =
        LedgerApp.startWeb(
            List.of(TraceSupport.InMemorySpans.class, Recording.class),
            "--ledger.messaging.enabled=true",
            "--ledger.messaging.bootstrap-servers=" + SharedKafka.bootstrapServers(),
            "--ledger.messaging.relay-enabled=true",
            "--ledger.messaging.relay-interval=100ms",
            "--ledger.messaging.consumers-enabled=true",
            "--ledger.messaging.consumer-group-prefix=trace-" + UUID.randomUUID());
    http = RestTestClient.bindToServer().baseUrl("http://localhost:" + LedgerApp.port(app)).build();
    jdbc = app.getBean(JdbcClient.class);
    tracing = app.getBean(Tracing.class);
    jdbc.sql(
            "INSERT INTO counterparty VALUES ('CP-001', 'Synthetic Borrower One', 1000000.00, 'HKD')")
        .update();
    jdbc.sql("INSERT INTO security VALUES (?, 'Synthetic HK Equity', 'HKD', 41.26, 'HKD')")
        .param(ISIN)
        .update();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static String traceIdOf(String traceparent) {
    return traceparent.split("-")[1];
  }

  private static List<SpanData> spansOfTrace(String traceId) {
    return TraceSupport.spans().getFinishedSpanItems().stream()
        .filter(span -> span.getTraceId().equals(traceId))
        .toList();
  }

  private static String openBody() {
    return """
        {"counterpartyId":"CP-001","isin":"%s","quantity":1000,
         "collateral":{"amount":"50000.00","currency":"HKD"},
         "rule":{"type":"FLAT","rate":"0.0365"},"tradeDate":"2026-09-01"}
        """
        .formatted(ISIN);
  }

  @Test
  @DisplayName(
      "HTTP open -> outbox row -> Kafka record -> price update -> margin call -> notice: one trace")
  void oneTraceAcrossTheRelayAndTheConsumers() {
    // 1. The HTTP request: its trace id is the response's, the outbox row's and the record's.
    EntityExchangeResult<String> opened =
        http.post()
            .uri("/loans")
            .header("Idempotency-Key", "trace-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .body(openBody())
            .exchange()
            .returnResult(String.class);
    assertThat(opened.getStatus()).isEqualTo(HttpStatus.CREATED);
    String requestTrace = opened.getResponseHeaders().getFirst("X-Trace-Id");
    String position = jdbc.sql("SELECT id FROM loan_position").query(String.class).single();

    String stored =
        jdbc.sql("SELECT traceparent FROM outbox_event WHERE message_key = ?")
            .param(position)
            .query(String.class)
            .single();
    assertThat(traceIdOf(stored)).isEqualTo(requestTrace);

    ConsumerRecord<String, String> onTheWire =
        await()
            .atMost(Duration.ofSeconds(60))
            .until(
                () ->
                    SharedKafka.readAll(Topics.POSITION_EVENTS).stream()
                        .filter(r -> position.equals(r.key()))
                        .findFirst()
                        .orElse(null),
                r -> r != null);
    assertThat(traceIdOf(header(onTheWire, "traceparent"))).isEqualTo(requestTrace);

    // 2. A feed publishes a price inside a span of its own; the margin call it causes is raised on
    // a
    //    consumer thread, stored in the outbox, sent by the relay, and consumed on another one.
    String[] feed = new String[2];
    tracing.inSpan(
        "feed publishes price",
        null,
        () -> {
          feed[0] = tracing.currentTraceId().orElseThrow();
          feed[1] = tracing.currentTraceparent().orElseThrow();
          app.getBean(PublishPriceUpdate.class)
              .publish(
                  new PriceUpdated(
                      UUID.randomUUID(),
                      Instant.now(),
                      new Isin(ISIN),
                      Money.of("60.00", HKD),
                      System
                          .currentTimeMillis() /* newer than any version the shared topic's history holds */));
        });
    await()
        .atMost(Duration.ofSeconds(90))
        .until(() -> NOTICES.stream().anyMatch(n -> n.position().value().equals(position)));
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                spansOfTrace(feed[0]).stream()
                        .filter(s -> s.getName().equals("consume " + Topics.MARGIN_CALLS))
                        .count()
                    == 1);

    List<SpanData> spans = spansOfTrace(feed[0]);
    SpanData root =
        spans.stream()
            .filter(s -> s.getName().equals("feed publishes price"))
            .findFirst()
            .orElseThrow();
    SpanData priceConsumer =
        spans.stream()
            .filter(s -> s.getName().equals("consume " + Topics.PRICE_UPDATES))
            .findFirst()
            .orElseThrow();
    SpanData callConsumer =
        spans.stream()
            .filter(s -> s.getName().equals("consume " + Topics.MARGIN_CALLS))
            .findFirst()
            .orElseThrow();

    assertThat(priceConsumer.getParentSpanId()).isEqualTo(root.getSpanId());
    assertThat(callConsumer.getParentSpanId())
        .as("the margin call was written inside the price consumer's span")
        .isEqualTo(priceConsumer.getSpanId());
    String callTraceparent =
        jdbc.sql(
                "SELECT traceparent FROM outbox_event WHERE type = 'MarginCallRaised' AND message_key = ?")
            .param(position)
            .query(String.class)
            .single();
    assertThat(callTraceparent).contains(priceConsumer.getSpanId());

    Evidence.write(
        "12-trace-across-kafka.txt",
        """
        HTTP POST /loans            trace %s
          outbox_event.traceparent  %s
          kafka record header       %s
        feed -> price -> margin call -> notice, trace %s
          %-28s span %s  parent %s
          %-28s span %s  parent %s
          %-28s span %s  parent %s
        """
            .formatted(
                requestTrace,
                stored,
                header(onTheWire, "traceparent"),
                feed[0],
                root.getName(),
                root.getSpanId(),
                root.getParentSpanId(),
                priceConsumer.getName(),
                priceConsumer.getSpanId(),
                priceConsumer.getParentSpanId(),
                callConsumer.getName(),
                callConsumer.getSpanId(),
                callConsumer.getParentSpanId()));
  }

  @Test
  @DisplayName(
      "without the stored context the trace is broken at the outbox: the consumer starts anew")
  void reproducesTraceBrokenAtOutbox() {
    // See docs/decisions/12-resilience-observability.md
    // The outbox as it was before stage 12: it stores no trace context. Within a request's span the
    // event is written; the relay sends it later, and the consumer has nothing to continue.
    StoredOutbox unstamped = new StoredOutbox(CODEC, app.getBean(OutboxStore.class));
    TransactionTemplate tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
    UUID eventId = UUID.randomUUID();
    String[] root = new String[1];
    tracing.inSpan(
        "request that writes without context",
        null,
        () -> {
          root[0] = tracing.currentTraceId().orElseThrow();
          tx.executeWithoutResult(
              status ->
                  unstamped.append(
                      new MarginCallRaised(
                          eventId,
                          Instant.now(),
                          new LoanPositionId("BROKEN-" + eventId),
                          new CounterpartyId("CP-001"),
                          new Isin(ISIN),
                          LocalDate.of(2026, 10, 1),
                          Money.of("1.00", HKD))));
        });

    await()
        .atMost(Duration.ofSeconds(90))
        .until(
            () -> NOTICES.stream().anyMatch(n -> n.position().value().equals("BROKEN-" + eventId)));

    assertThat(
            jdbc.sql("SELECT traceparent FROM outbox_event WHERE id = ?")
                .param(eventId)
                .query(String.class)
                .optional())
        .isEmpty();
    assertThat(spansOfTrace(root[0]).stream().map(SpanData::getName))
        .as("the consumer's span is not in the request's trace")
        .doesNotContain("consume " + Topics.MARGIN_CALLS);
  }
}
