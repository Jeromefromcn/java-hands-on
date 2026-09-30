package com.jerome.slledger.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.OutboxMessage;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Why the price topic is keyed by ISIN: the key decides the partition, and only one partition's
 * order is kept. See docs/decisions/10-messaging.md.
 */
@Timeout(120)
class PartitionKeyIT {

  private static final int PARTITIONS = 3;
  private static final Money PRICE = Money.of("41.30", Currency.getInstance("HKD"));
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static KafkaTemplate<String, String> template;

  @BeforeAll
  static void start() {
    SharedKafka.start();
    template =
        KafkaProducers.template(
            SharedKafka.bootstrapServers(), Duration.ofSeconds(20), Duration.ofSeconds(10));
  }

  @AfterAll
  static void finish() {
    template.destroy();
    Evidence.write("10-partition-key.txt", EVIDENCE.toString());
  }

  /**
   * One acknowledged record to every partition before a burst. A burst into a topic created a
   * moment ago made an idempotent producer loop on NOT_LEADER_OR_FOLLOWER and then
   * OUT_OF_ORDER_SEQUENCE_NUMBER until its 120 s delivery timeout, twice in full builds and never
   * when the same test ran alone. The product's sender is not exposed to it in the same way: it
   * sends one message at a time and its delivery timeout is seconds, so a stall is an
   * EventSendException and the relay tries again.
   */
  private static void warmUp(KafkaProducer<String, String> producer, String topic)
      throws Exception {
    for (int partition = 0; partition < PARTITIONS; partition++) {
      producer
          .send(new ProducerRecord<>(topic, partition, "warm-up", "warm-up"))
          .get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  private static OutboxMessage message(String topic, PriceUpdated event, String key) {
    return new OutboxMessage(
        event.id(),
        topic,
        key,
        event.type(),
        new JsonEventCodec().encode(event),
        event.occurredAt());
  }

  @Test
  @DisplayName("every update of one ISIN lands on one partition, in the order it was sent")
  void sameSecurityOnePartitionInOrder() {
    String topic = SharedKafka.uniqueTopic("prices");
    SharedKafka.createTopic(topic, PARTITIONS);
    KafkaEventSender sender = new KafkaEventSender(template, Duration.ofSeconds(30));
    Isin isin = new Isin("HK0000000007");

    for (long version = 1; version <= 60; version++) {
      PriceUpdated update =
          new PriceUpdated(UUID.randomUUID(), Instant.now(), isin, PRICE, version);
      sender.send(message(topic, update, update.key()));
    }

    List<ConsumerRecord<String, String>> records = SharedKafka.readAll(topic);
    Set<Integer> partitions =
        records.stream().map(ConsumerRecord::partition).collect(Collectors.toSet());
    assertThat(records).hasSize(60);
    assertThat(partitions).hasSize(1);
    List<Long> versions =
        records.stream()
            .map(record -> new JsonEventCodec().decode("PriceUpdated", record.value()))
            .map(event -> ((PriceUpdated) event).version())
            .toList();
    assertThat(versions).isSorted();
    EVIDENCE
        .append("key = ISIN: 60 updates of one security -> partitions used=")
        .append(partitions)
        .append(", versions read back in send order=")
        .append(versions.equals(versions.stream().sorted().toList()))
        .append('\n');
  }

  @Test
  @DisplayName("1,000 securities spread over the partitions")
  void distribution() throws Exception {
    String topic = SharedKafka.uniqueTopic("spread");
    SharedKafka.createTopic(topic, PARTITIONS);
    Map<Integer, Integer> perPartition = new TreeMap<>();
    List<Future<RecordMetadata>> sent = new ArrayList<>();
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(SharedKafka.producerProperties())) {
      warmUp(producer, topic);
      for (int i = 0; i < 1000; i++) {
        String isin = "HK%09d".formatted(i * 7L) + (i % 10);
        sent.add(producer.send(new ProducerRecord<>(topic, isin, "x")));
      }
      for (Future<RecordMetadata> future : sent) {
        perPartition.merge(future.get().partition(), 1, Integer::sum);
      }
    }

    assertThat(perPartition).hasSize(PARTITIONS);
    assertThat(perPartition.values()).allSatisfy(count -> assertThat(count).isBetween(250, 420));
    EVIDENCE
        .append("key = ISIN: 1,000 securities over ")
        .append(PARTITIONS)
        .append(" partitions -> ")
        .append(perPartition)
        .append('\n');
  }

  @Test
  @DisplayName("a random key sends one security's updates to several partitions")
  void reproducesRandomKeySplitsOneSecurity() throws Exception {
    // See docs/decisions/10-messaging.md
    String topic = SharedKafka.uniqueTopic("randomkey");
    SharedKafka.createTopic(topic, PARTITIONS);
    Set<Integer> partitions = new java.util.TreeSet<>();
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(SharedKafka.producerProperties())) {
      warmUp(producer, topic);
      List<Future<RecordMetadata>> sent = new ArrayList<>();
      for (int i = 0; i < 60; i++) {
        // The same security, a key that has nothing to do with it.
        sent.add(producer.send(new ProducerRecord<>(topic, UUID.randomUUID().toString(), "v" + i)));
      }
      for (Future<RecordMetadata> future : sent) {
        partitions.add(future.get().partition());
      }
    }

    // Kafka orders a partition and nothing across partitions: with these partitions, "v2 was sent
    // after v1" is no longer something a consumer can rely on.
    assertThat(partitions).hasSizeGreaterThan(1);
    EVIDENCE
        .append("key = random UUID: 60 updates of ONE security -> partitions used=")
        .append(partitions)
        .append(" (order is kept per partition only)\n");
  }
}
