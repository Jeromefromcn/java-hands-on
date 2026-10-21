package com.jerome.slledger.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.JsonEventCodec;
import com.jerome.slledger.adapter.messaging.KafkaEventSender;
import com.jerome.slledger.adapter.messaging.PriceUpdateListener;
import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.application.messaging.ApplyPriceUpdate;
import com.jerome.slledger.application.messaging.IntegrationEvent;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.OutboxMessage;
import com.jerome.slledger.application.messaging.PublishPriceUpdate;
import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.listener.MessageListener;

/**
 * Two updates of one price arriving in the wrong order, and what keeps the stored price right: the
 * partition key (they cannot arrive out of order) and the version (it does not matter if they do).
 * The wrong order is made by pausing the partition that holds v1, which is what a consumer with a
 * backlog on that partition looks like from outside.
 */
@Timeout(180)
class PriceOrderingIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Isin ISIN = new Isin("HK0000000001");
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static MessagingLedger ledger;
  private static JsonEventCodec codec;

  @BeforeAll
  static void start() {
    ledger = MessagingLedger.start();
    codec = new JsonEventCodec();
  }

  @AfterAll
  static void stop() {
    ledger.close();
    Evidence.write("10-ordering.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void reset() {
    ledger.reset();
    ledger
        .jdbc
        .sql("UPDATE security SET market_price_amount = 41.26, price_version = 0 WHERE isin = ?")
        .param(ISIN.value())
        .update();
  }

  private static PriceUpdated update(String price, long version) {
    return new PriceUpdated(UUID.randomUUID(), Instant.now(), ISIN, Money.of(price, HKD), version);
  }

  private BigDecimal storedPrice() {
    return ledger
        .jdbc
        .sql("SELECT market_price_amount FROM security WHERE isin = ?")
        .param(ISIN.value())
        .query(BigDecimal.class)
        .single();
  }

  private long storedVersion() {
    return ledger
        .jdbc
        .sql("SELECT price_version FROM security WHERE isin = ?")
        .param(ISIN.value())
        .query(Long.class)
        .single();
  }

  /** Sends the event; {@code partition} null lets the key decide. Returns where it landed. */
  private static int produce(String topic, Integer partition, String key, PriceUpdated event) {
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(SharedKafka.producerProperties())) {
      ProducerRecord<String, String> record =
          new ProducerRecord<>(topic, partition, key, codec.encode(event));
      record
          .headers()
          .add(KafkaEventSender.EVENT_TYPE, PriceUpdated.TYPE.getBytes(StandardCharsets.UTF_8));
      record
          .headers()
          .add(KafkaEventSender.MESSAGE_ID, event.id().toString().getBytes(StandardCharsets.UTF_8));
      RecordMetadata metadata = producer.send(record).get();
      return metadata.partition();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Hands the listener every record of {@code accept} on the topic, the records of {@code
   * backlogPartition} last: it is paused until everything else has been delivered.
   */
  private static void deliver(
      String topic,
      int backlogPartition,
      Predicate<ConsumerRecord<String, String>> accept,
      MessageListener<String, String> listener) {
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(SharedKafka.consumerProperties("order-" + UUID.randomUUID()))) {
      List<TopicPartition> all =
          consumer.partitionsFor(topic).stream()
              .map(info -> new TopicPartition(topic, info.partition()))
              .toList();
      consumer.assign(all);
      consumer.seekToBeginning(all);
      Map<TopicPartition, Long> ends = consumer.endOffsets(all);
      TopicPartition backlog = new TopicPartition(topic, backlogPartition);
      consumer.pause(List.of(backlog));
      pump(consumer, all, ends, accept, listener, Set.of(backlog));
      consumer.resume(List.of(backlog));
      pump(consumer, all, ends, accept, listener, Set.of());
    }
  }

  private static void pump(
      KafkaConsumer<String, String> consumer,
      List<TopicPartition> all,
      Map<TopicPartition, Long> ends,
      Predicate<ConsumerRecord<String, String>> accept,
      MessageListener<String, String> listener,
      Set<TopicPartition> paused) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    Set<TopicPartition> open = new HashSet<>(all);
    open.removeAll(paused);
    open.removeIf(tp -> ends.get(tp) == 0);
    while (!open.isEmpty() && System.nanoTime() < deadline) {
      for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
        if (accept.test(record)) {
          listener.onMessage(record);
        }
      }
      open.removeIf(tp -> consumer.position(tp) >= ends.get(tp));
    }
  }

  /** Last write wins: what a consumer with no version check does. Test-only. */
  private MessageListener<String, String> lastWriteWins() {
    return record -> {
      PriceUpdated update = (PriceUpdated) codec.decode(PriceUpdated.TYPE, record.value());
      ledger
          .jdbc
          .sql("UPDATE security SET market_price_amount = ?, price_version = ? WHERE isin = ?")
          .params(update.price().amount(), update.version(), update.security().value())
          .update();
    };
  }

  private MessageListener<String, String> gated() {
    return new PriceUpdateListener(codec, ledger.app.getBean(ApplyPriceUpdate.class));
  }

  @Test
  @DisplayName(
      "v2 arrives before v1 on different partitions: last-write-wins ends on the old price")
  void reproducesOldPriceOverwritesNew() {
    // See docs/decisions/10-messaging.md
    String topic = SharedKafka.uniqueTopic("order-naive");
    SharedKafka.createTopic(topic, 2);
    // A key that has nothing to do with the security put the two updates on different partitions.
    produce(topic, 0, "some-other-key-1", update("41.30", 1));
    produce(topic, 1, "some-other-key-2", update("43.10", 2));

    deliver(topic, 0, record -> true, lastWriteWins());

    assertThat(storedPrice()).isEqualByComparingTo("41.30");
    assertThat(storedVersion()).isEqualTo(1);
    EVIDENCE.append(
        "random key, v1 on partition 0 (backlog), v2 on partition 1, last-write-wins: delivered v2 then v1 -> stored price 41.30 at version 1 (v2's 43.10 was overwritten)\n");
  }

  @Test
  @DisplayName(
      "keyed by ISIN, both updates share a partition: even the naive consumer sees them in order")
  void partitionKeyKeepsThemInOrder() {
    String topic = SharedKafka.uniqueTopic("order-keyed");
    SharedKafka.createTopic(topic, 2);
    int p1 = produce(topic, null, ISIN.value(), update("41.30", 1));
    int p2 = produce(topic, null, ISIN.value(), update("43.10", 2));
    assertThat(p1).isEqualTo(p2);

    // Whatever partition is behind, v1 and v2 are behind it together and in order.
    deliver(topic, p1, record -> true, lastWriteWins());

    assertThat(storedPrice()).isEqualByComparingTo("43.10");
    assertThat(storedVersion()).isEqualTo(2);
    EVIDENCE
        .append("key = ISIN, both on partition ")
        .append(p1)
        .append(
            " (paused as the backlog): last-write-wins delivered v1 then v2 -> stored price 43.10 at version 2\n");
  }

  @Test
  @DisplayName("with the version gate, the same wrong order still ends on the newest price")
  void versionGateIgnoresTheLateUpdate() {
    String topic = SharedKafka.uniqueTopic("order-gated");
    SharedKafka.createTopic(topic, 2);
    produce(topic, 0, "some-other-key-1", update("41.30", 1));
    produce(topic, 1, "some-other-key-2", update("43.10", 2));

    deliver(topic, 0, record -> true, gated());

    assertThat(storedPrice()).isEqualByComparingTo("43.10");
    assertThat(storedVersion()).isEqualTo(2);
    EVIDENCE.append(
        "random key, same arrival order (v2 then v1), version gate: stored price 43.10 at version 2; v1 was ignored, not applied and not an error\n");
  }

  private static OpenPositionCommand open(String collateral) {
    return new OpenPositionCommand(
        new IdempotencyKey("K-" + UUID.randomUUID()),
        new CounterpartyId("CP-001"),
        ISIN,
        1_000,
        Money.of(collateral, HKD),
        new FlatRate(Rate.of("0.0365")),
        LocalDate.of(2026, 9, 1));
  }

  private List<IntegrationEvent> outboxedCalls() {
    List<OutboxMessage> rows =
        ledger
            .jdbc
            .sql(
                "SELECT id, topic, message_key, type, payload, created_at FROM outbox_event"
                    + " WHERE type = 'MarginCallRaised' ORDER BY seq")
            .query(
                (rs, n) ->
                    new OutboxMessage(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()))
            .list();
    List<IntegrationEvent> events = new ArrayList<>();
    for (OutboxMessage row : rows) {
      events.add(codec.decode(row.type(), row.payload()));
    }
    return events;
  }

  @Test
  @DisplayName(
      "a price update over Kafka revalues the position, records one call in the outbox, and a redelivery adds none")
  void priceDropRaisesOneMarginCall() {
    LoanTrade trade = ledger.app.getBean(OpenPosition.class).open(open("50000.00"));
    String topic = SharedKafka.uniqueTopic("prices-e2e");
    SharedKafka.createTopic(topic, 1);
    PriceUpdated drop = update("60.00", 1);
    PriceUpdated recovery = update("41.30", 2);
    produce(topic, null, ISIN.value(), drop);
    produce(topic, null, ISIN.value(), drop); // the same message, delivered twice
    produce(topic, null, ISIN.value(), recovery);

    deliver(topic, 0, record -> true, gated());

    List<IntegrationEvent> calls = outboxedCalls();
    assertThat(calls).hasSize(1);
    MarginCallRaised call = (MarginCallRaised) calls.getFirst();
    assertThat(call.position()).isEqualTo(trade.position());
    assertThat(call.shortfall()).isEqualTo(Money.of("10000.00", HKD));
    // 60.00 x 1,000 = 60,000 against 50,000 collateral. The recovery to 41.30 covers it again.
    assertThat(storedPrice()).isEqualByComparingTo("41.30");
    assertThat(storedVersion()).isEqualTo(2);
    EVIDENCE.append(
        "price 60.00 v1 (delivered twice), then 41.30 v2, position 1,000 shares against 50,000.00 HKD collateral: MarginCallRaised rows in the outbox=1, shortfall 10000.00 HKD, stored price 41.30 at version 2\n");
  }

  @Test
  @DisplayName("PublishPriceUpdate puts the update on the price topic keyed by ISIN")
  void publishedUpdateIsKeyedByIsin() {
    PriceUpdated event = update("41.30", 9);

    ledger.app.getBean(PublishPriceUpdate.class).publish(event);

    List<ConsumerRecord<String, String>> onTopic =
        SharedKafka.readAll("sl.price-updates").stream()
            // The topic is shared, and other tests put records on it without this header.
            .filter(r -> r.headers().lastHeader("messageId") != null)
            .filter(
                r ->
                    event
                        .id()
                        .toString()
                        .equals(
                            new String(
                                r.headers().lastHeader("messageId").value(),
                                StandardCharsets.UTF_8)))
            .toList();
    assertThat(onTopic).hasSize(1);
    assertThat(onTopic.getFirst().key()).isEqualTo(ISIN.value());
  }
}
