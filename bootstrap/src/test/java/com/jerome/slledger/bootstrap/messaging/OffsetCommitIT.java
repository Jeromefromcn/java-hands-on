package com.jerome.slledger.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.JsonEventCodec;
import com.jerome.slledger.adapter.messaging.KafkaConsumers;
import com.jerome.slledger.adapter.messaging.KafkaEventSender;
import com.jerome.slledger.adapter.messaging.MarginCallListener;
import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.application.messaging.IdempotentConsumer;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.MarginCallNotifier;
import com.jerome.slledger.application.messaging.NoticeChannel;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * When the offset is committed relative to the effect decides which failure a consumer has: before,
 * it loses the message; after, it may see it twice. The consumers here are raw {@code
 * KafkaConsumer}s, so the moment of the commit is the test's, and a "crash" is closing the consumer
 * without committing. Waiting to see that nothing arrives is bounded real time, and says so.
 */
@Timeout(240)
class OffsetCommitIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final StringBuilder EVIDENCE = new StringBuilder();
  private static final Duration NOTHING_ARRIVES_WITHIN = Duration.ofSeconds(6);

  private static MessagingLedger ledger;
  private static final JsonEventCodec CODEC = new JsonEventCodec();

  private String topic;
  private String group;
  private MarginCallRaised event;

  @BeforeAll
  static void start() {
    ledger = MessagingLedger.start();
  }

  @AfterAll
  static void stop() {
    ledger.close();
    Evidence.write("10-offset-commit.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void setUp() throws Exception {
    ledger.reset();
    ledger.jdbc.sql("DELETE FROM consumed_message").update();
    topic = SharedKafka.uniqueTopic("offsets");
    group = "group-" + UUID.randomUUID();
    SharedKafka.createTopic(topic, 1);
    event =
        new MarginCallRaised(
            UUID.randomUUID(),
            Instant.now(),
            new LoanPositionId("P-1"),
            new CounterpartyId("CP-001"),
            new Isin("HK0000000001"),
            LocalDate.of(2026, 9, 30),
            Money.of("10000.00", HKD));
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(SharedKafka.producerProperties())) {
      ProducerRecord<String, String> record =
          new ProducerRecord<>(topic, event.key(), CODEC.encode(event));
      record
          .headers()
          .add(KafkaEventSender.MESSAGE_ID, event.id().toString().getBytes(StandardCharsets.UTF_8));
      record
          .headers()
          .add(KafkaEventSender.EVENT_TYPE, event.type().getBytes(StandardCharsets.UTF_8));
      producer.send(record).get();
    }
  }

  private KafkaConsumer<String, String> consumer(boolean autoCommit) {
    Properties properties = SharedKafka.consumerProperties(group);
    if (autoCommit) {
      properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
      properties.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "100");
    }
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
    consumer.subscribe(List.of(topic));
    return consumer;
  }

  private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
        return record;
      }
    }
    throw new AssertionError("no record within 60 s");
  }

  /**
   * How many records arrive in a bounded wait. Real time: absence can only be observed by waiting.
   */
  private static int recordsWithin(KafkaConsumer<String, String> consumer, Duration wait) {
    long deadline = System.nanoTime() + wait.toNanos();
    int received = 0;
    while (System.nanoTime() < deadline) {
      received += consumer.poll(Duration.ofMillis(500)).count();
    }
    return received;
  }

  private static void failingEffect() {
    throw new IllegalStateException("the effect failed");
  }

  @Test
  @DisplayName(
      "commit, then process, then fail: a new consumer in the group never sees the message")
  void reproducesLossWhenOffsetCommittedBeforeProcessing() {
    // See docs/decisions/10-messaging.md
    int effects = 0;
    try (KafkaConsumer<String, String> first = consumer(false)) {
      pollOne(first);
      first.commitSync();
      try {
        failingEffect();
        effects++;
      } catch (IllegalStateException failed) {
        // The application logged it and moved on, or crashed here: either way the offset is spent.
      }
    }

    int redelivered;
    try (KafkaConsumer<String, String> second = consumer(false)) {
      redelivered = recordsWithin(second, NOTHING_ARRIVES_WITHIN);
    }

    assertThat(effects).isZero();
    assertThat(redelivered).isZero();
    EVIDENCE
        .append(
            "commit before processing, effect failed: effects applied=0, records a new consumer in the group receives within ")
        .append(NOTHING_ARRIVES_WITHIN.toSeconds())
        .append(" s=0 (the message is lost)\n");
  }

  @Test
  @DisplayName("auto-commit commits the offset of a record the application failed to handle")
  void reproducesLossWithAutoCommit() throws Exception {
    // See docs/decisions/10-messaging.md
    try (KafkaConsumer<String, String> first = consumer(true)) {
      pollOne(first);
      try {
        failingEffect();
      } catch (IllegalStateException failed) {
        // Caught and logged, as a listener's catch-all does.
      }
      // Real time is the subject: the interval must pass before the next poll commits.
      Thread.sleep(200);
      first.poll(
          Duration.ofMillis(
              200)); // the loop goes round; this poll commits the offsets returned by the last
    }

    int redelivered;
    try (KafkaConsumer<String, String> second = consumer(false)) {
      redelivered = recordsWithin(second, NOTHING_ARRIVES_WITHIN);
    }

    assertThat(redelivered).isZero();
    EVIDENCE.append(
        "enable.auto.commit=true (interval 100 ms), effect failed and was caught: the next poll committed it; records a new consumer receives=0 (the message is lost)\n");
  }

  @Test
  @DisplayName(
      "process, then die before committing: the message comes again, the effect runs twice, and the guard makes it once")
  void reproducesDuplicateWhenProcessedButNotCommitted() {
    // See docs/decisions/10-messaging.md
    List<String> unguardedEffects = new ArrayList<>();
    for (int attempt = 1; attempt <= 2; attempt++) {
      try (KafkaConsumer<String, String> consumer = consumer(false)) {
        ConsumerRecord<String, String> record = pollOne(consumer);
        unguardedEffects.add("effect for offset " + record.offset());
        // Died here: the effect happened, the commit did not. close() without commitSync.
      }
    }

    List<NoticeChannel.MarginCallNotice> notices = new ArrayList<>();
    MarginCallNotifier notifier =
        new MarginCallNotifier(ledger.app.getBean(IdempotentConsumer.class), notices::add);
    MarginCallListener listener = new MarginCallListener(CODEC, notifier);
    for (int attempt = 1; attempt <= 2; attempt++) {
      try (KafkaConsumer<String, String> consumer = consumer(false)) {
        listener.onMessage(pollOne(consumer));
      }
    }

    assertThat(unguardedEffects).hasSize(2);
    assertThat(notices).hasSize(1);
    EVIDENCE
        .append(
            "effect done, process died before the commit, restarted twice: effects without the guard=")
        .append(unguardedEffects.size())
        .append(", notices sent with the guard=")
        .append(notices.size())
        .append('\n');
  }

  private Map<TopicPartition, OffsetAndMetadata> committed() throws Exception {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", SharedKafka.bootstrapServers()))) {
      return admin
          .listConsumerGroupOffsets(group)
          .partitionsToOffsetAndMetadata()
          .get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("the product container commits after the listener returns, one record at a time")
  void productContainerCommitsAfterTheEffect() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Map<TopicPartition, OffsetAndMetadata>> whileHandling = new AtomicReference<>();
    ConcurrentMessageListenerContainer<String, String> container =
        KafkaConsumers.container(
            KafkaConsumers.Settings.of(SharedKafka.bootstrapServers(), group),
            topic,
            record -> {
              inside.countDown();
              try {
                release.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            null);
    container.start();
    try {
      assertThat(container.getContainerProperties().getAckMode())
          .isEqualTo(ContainerProperties.AckMode.RECORD);
      assertThat(inside.await(60, TimeUnit.SECONDS)).isTrue();
      whileHandling.set(committed());
      release.countDown();

      TopicPartition partition = new TopicPartition(topic, 0);
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      long after = -1;
      while (System.nanoTime() < deadline && after < 1) {
        OffsetAndMetadata offset = committed().get(partition);
        after = offset == null ? -1 : offset.offset();
      }

      assertThat(whileHandling.get()).isEmpty();
      assertThat(after).isEqualTo(1);
      EVIDENCE
          .append(
              "product container: while the listener was still running, committed offsets for the group=")
          .append(whileHandling.get().size())
          .append("; after it returned, committed offset=")
          .append(after)
          .append(" (commit follows the effect)\n");
    } finally {
      release.countDown();
      container.stop();
    }
  }
}
