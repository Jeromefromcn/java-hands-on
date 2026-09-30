package com.jerome.slledger.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/**
 * Two ways a consumer that cannot keep up fails: the lag grows, and if one poll's worth of work
 * takes longer than {@code max.poll.interval.ms}, the consumer is removed from its group and its
 * uncommitted batch is delivered again. Real elapsed time is the subject of both, so both sleep and
 * both are bounded by {@code @Timeout}; the sleeps stand in for a slow database.
 */
@Timeout(300)
class BackpressureIT {

  private static final StringBuilder EVIDENCE = new StringBuilder();

  @BeforeAll
  static void start() {
    SharedKafka.start();
  }

  @AfterAll
  static void finish() {
    Evidence.write("10-backpressure.txt", EVIDENCE.toString());
  }

  // ---- lag -------------------------------------------------------------------------------

  private static long lag(Admin admin, String topic, String group) throws Exception {
    TopicPartition partition = new TopicPartition(topic, 0);
    long end =
        admin
            .listOffsets(Map.of(partition, OffsetSpec.latest()))
            .partitionResult(partition)
            .get(10, TimeUnit.SECONDS)
            .offset();
    OffsetAndMetadata committed =
        admin
            .listConsumerGroupOffsets(group)
            .partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS)
            .get(partition);
    return end - (committed == null ? 0 : committed.offset());
  }

  @Test
  @DisplayName(
      "a producer faster than the consumer makes the lag grow, and it drains once the producer stops")
  void lagGrowsWhileTheProducerOutpacesTheConsumer() throws Exception {
    String topic = SharedKafka.uniqueTopic("lag");
    String group = "lag-" + UUID.randomUUID();
    SharedKafka.createTopic(topic, 1);
    AtomicInteger handled = new AtomicInteger();
    ConcurrentMessageListenerContainer<String, String> container =
        KafkaConsumers.container(
            KafkaConsumers.Settings.of(SharedKafka.bootstrapServers(), group),
            topic,
            record -> {
              try {
                Thread.sleep(20); // the consumer's work: about 50 records a second
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              handled.incrementAndGet();
            },
            null);
    container.start();
    AtomicBoolean producing = new AtomicBoolean(true);
    int produced = 0;
    List<Long> samples = new ArrayList<>();
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", SharedKafka.bootstrapServers()));
        KafkaProducer<String, String> producer =
            new KafkaProducer<>(SharedKafka.producerProperties())) {
      long startedNanos = System.nanoTime();
      long nextSample = 1;
      while (System.nanoTime() - startedNanos < Duration.ofSeconds(6).toNanos()) {
        // About 100 records a second: two every 20 ms.
        producer.send(new ProducerRecord<>(topic, "K", "m" + produced++));
        producer.send(new ProducerRecord<>(topic, "K", "m" + produced++));
        Thread.sleep(20);
        long elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000L;
        if (elapsedSeconds >= nextSample) {
          producer.flush();
          samples.add(lag(admin, topic, group));
          nextSample++;
        }
      }
      producer.flush();
      producing.set(false);
      long drainStarted = System.nanoTime();
      long lagAtStop = lag(admin, topic, group);
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (lag(admin, topic, group) > 0 && System.nanoTime() < deadline) {
        Thread.sleep(200);
      }
      long drainMs = (System.nanoTime() - drainStarted) / 1_000_000;

      assertThat(samples.getLast()).isGreaterThan(samples.getFirst());
      assertThat(samples.getLast()).isGreaterThan(100);
      assertThat(lag(admin, topic, group)).isZero();
      EVIDENCE
          .append(
              "producer ~100 records/s, consumer ~50 records/s (20 ms each), one partition: lag sampled each second for 6 s = ")
          .append(samples)
          .append("; produced ")
          .append(produced)
          .append(", lag when the producer stopped=")
          .append(lagAtStop)
          .append(", drained to 0 in ")
          .append(drainMs)
          .append(" ms after it stopped\n");
    } finally {
      container.stop();
    }
  }

  // ---- being thrown out of the group ------------------------------------------------------

  private record Outcome(
      boolean committed, int partitionsLostOrRevoked, long firstOffsetOnNextPoll) {}

  private static Outcome run(int maxPollRecords, Duration perRecord, Duration maxPollInterval)
      throws Exception {
    String topic = SharedKafka.uniqueTopic("kick");
    SharedKafka.createTopic(topic, 1);
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(SharedKafka.producerProperties())) {
      for (int i = 0; i < 20; i++) {
        producer.send(new ProducerRecord<>(topic, "K", "m" + i));
      }
      producer.flush();
    }
    Properties properties = SharedKafka.consumerProperties("kick-" + UUID.randomUUID());
    properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(maxPollRecords));
    properties.put(
        ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, String.valueOf(maxPollInterval.toMillis()));
    AtomicInteger lostOrRevoked = new AtomicInteger();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
      consumer.subscribe(
          List.of(topic),
          new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
              lostOrRevoked.addAndGet(partitions.size());
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
              lostOrRevoked.addAndGet(partitions.size());
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {}
          });
      List<ConsumerRecord<String, String>> batch = new ArrayList<>();
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (batch.isEmpty() && System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(500)).forEach(batch::add);
      }
      // A rebalance on the first join revokes nothing that was held: start counting from here.
      lostOrRevoked.set(0);
      for (int i = 0; i < batch.size(); i++) {
        Thread.sleep(perRecord.toMillis()); // the batch's work
      }
      boolean committed;
      try {
        consumer.commitSync();
        committed = true;
      } catch (CommitFailedException removed) {
        committed = false;
      }
      long firstOffset = -1;
      deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (firstOffset < 0 && System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          firstOffset = record.offset();
          break;
        }
      }
      return new Outcome(committed, lostOrRevoked.get(), firstOffset);
    }
  }

  @Test
  @DisplayName(
      "a batch that takes longer than max.poll.interval.ms gets the consumer removed and the batch delivered again")
  void reproducesKickedOutForSlowProcessing() throws Exception {
    // See docs/decisions/10-messaging.md
    Duration interval = Duration.ofSeconds(3);

    Outcome slow = run(5, Duration.ofSeconds(1), interval);
    Outcome fastRecords = run(5, Duration.ofMillis(400), interval);
    Outcome smallerBatches = run(2, Duration.ofSeconds(1), interval);

    // 5 records x 1 s = 5 s of work between polls, against 3 s allowed.
    assertThat(slow.committed()).isFalse();
    assertThat(slow.firstOffsetOnNextPoll()).isZero();
    assertThat(slow.partitionsLostOrRevoked()).isGreaterThanOrEqualTo(1);
    // 5 x 0.4 s = 2 s, and 2 x 1 s = 2 s: both under 3 s.
    assertThat(fastRecords.committed()).isTrue();
    assertThat(fastRecords.firstOffsetOnNextPoll()).isEqualTo(5);
    assertThat(fastRecords.partitionsLostOrRevoked()).isZero();
    assertThat(smallerBatches.committed()).isTrue();
    assertThat(smallerBatches.firstOffsetOnNextPoll()).isEqualTo(2);
    assertThat(smallerBatches.partitionsLostOrRevoked()).isZero();
    EVIDENCE
        .append(
            "max.poll.interval.ms 3000: max.poll.records=5 x 1000 ms per record = 5000 ms between polls -> commit failed (CommitFailedException), partitions lost/revoked=")
        .append(slow.partitionsLostOrRevoked())
        .append(", next poll started again at offset ")
        .append(slow.firstOffsetOnNextPoll())
        .append(" (the 5 records handled were delivered again)\n")
        .append("max.poll.records=5 x 400 ms = 2000 ms -> commit ok, partitions lost=")
        .append(fastRecords.partitionsLostOrRevoked())
        .append(", next poll at offset ")
        .append(fastRecords.firstOffsetOnNextPoll())
        .append('\n')
        .append("max.poll.records=2 x 1000 ms = 2000 ms -> commit ok, partitions lost=")
        .append(smallerBatches.partitionsLostOrRevoked())
        .append(", next poll at offset ")
        .append(smallerBatches.firstOffsetOnNextPoll())
        .append('\n')
        .append(
            "rule: max.poll.records x worst time per record must stay below max.poll.interval.ms (the sleeps above are the worst time per record; a real worst case is the p99 of the handler, measured in production)\n");
  }
}
