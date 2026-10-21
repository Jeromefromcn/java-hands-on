package com.jerome.slledger.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/**
 * What a consumer does with the record in its hands when the application stops, as a pod's SIGTERM
 * stops it: Spring closes the context, the context stops the listener containers.
 *
 * <p>Five records arrive in one poll; each takes 400 ms. The container is stopped while the first
 * is being handled. Committed offsets are read from the broker, which is what the next member of
 * the group starts from. The rule this holds the consumer to: a record's offset is committed only
 * after its effect, so a stop either finishes a record and commits it, or leaves it uncommitted for
 * someone else; never commits one it did not finish. See docs/decisions/14-delivery.md.
 */
@Timeout(120)
class ConsumerShutdownIT {

  private static final StringBuilder EVIDENCE = new StringBuilder();

  @BeforeAll
  static void start() {
    SharedKafka.start();
  }

  @AfterAll
  static void finish() {
    Evidence.write("14-consumer-shutdown.txt", EVIDENCE.toString());
  }

  @Test
  void stopFinishesTheRecordInHandCommitsItAndLeavesTheRestToTheNextMember() throws Exception {
    Run run = stopDuringFirstRecord(true);

    assertThat(run.handledBeforeStop()).containsExactly("m0");
    assertThat(run.committedAfterStop()).isEqualTo(1);
    // The next member of the group starts at the committed offset: the rest, each once.
    assertThat(run.handledByNextMember()).containsExactly("m1", "m2", "m3", "m4");
    record("stopImmediate=true (what KafkaConsumers configures)", run);
  }

  @Test
  void withoutStopImmediateTheStopFirstHandlesTheRestOfThePoll() throws Exception {
    Run run = stopDuringFirstRecord(false);

    assertThat(run.handledBeforeStop()).containsExactly("m0", "m1", "m2", "m3", "m4");
    assertThat(run.committedAfterStop()).isEqualTo(5);
    assertThat(run.stopMillis()).isGreaterThanOrEqualTo(4 * 400 - 400);
    record("stopImmediate=false (Spring Kafka's default)", run);
  }

  private record Run(
      List<String> handledBeforeStop,
      long committedAfterStop,
      long stopMillis,
      List<String> handledByNextMember) {}

  private static void record(String label, Run run) {
    EVIDENCE
        .append(label)
        .append(": stop() called while m0 was in hand; returned after ")
        .append(run.stopMillis())
        .append(" ms; handled before the stop returned ")
        .append(run.handledBeforeStop())
        .append(", committed offset ")
        .append(run.committedAfterStop())
        .append("; the next member then handled ")
        .append(run.handledByNextMember())
        .append("\n");
  }

  private static Run stopDuringFirstRecord(boolean stopImmediate) throws Exception {
    String topic = SharedKafka.uniqueTopic("shutdown");
    String group = "shutdown-" + UUID.randomUUID();
    SharedKafka.createTopic(topic, 1);
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(SharedKafka.producerProperties())) {
      for (int i = 0; i < 5; i++) {
        producer.send(new ProducerRecord<>(topic, "K", "m" + i));
      }
      producer.flush();
    }

    List<String> handled = new CopyOnWriteArrayList<>();
    CountDownLatch firstInHand = new CountDownLatch(1);
    ConcurrentMessageListenerContainer<String, String> container =
        KafkaConsumers.container(
            KafkaConsumers.Settings.of(SharedKafka.bootstrapServers(), group),
            topic,
            record -> {
              firstInHand.countDown();
              sleep(400); // the effect: a write that takes a while
              handled.add(record.value());
            },
            null);
    container.getContainerProperties().setStopImmediate(stopImmediate);
    container.start();
    assertThat(firstInHand.await(60, TimeUnit.SECONDS)).isTrue();
    long started = System.nanoTime();
    container.stop();
    long stopMillis = (System.nanoTime() - started) / 1_000_000;
    List<String> before = List.copyOf(handled);
    long committed = committed(topic, group);

    List<String> next = new CopyOnWriteArrayList<>();
    ConcurrentMessageListenerContainer<String, String> nextMember =
        KafkaConsumers.container(
            KafkaConsumers.Settings.of(SharedKafka.bootstrapServers(), group),
            topic,
            record -> next.add(record.value()),
            null);
    nextMember.start();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (before.size() + next.size() < 5 && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    Thread.sleep(500); // anything delivered twice would arrive now
    nextMember.stop();
    return new Run(before, committed, stopMillis, List.copyOf(next));
  }

  private static long committed(String topic, String group) throws Exception {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", SharedKafka.bootstrapServers()))) {
      OffsetAndMetadata offset =
          admin
              .listConsumerGroupOffsets(group)
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(new TopicPartition(topic, 0));
      return offset == null ? 0 : offset.offset();
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
