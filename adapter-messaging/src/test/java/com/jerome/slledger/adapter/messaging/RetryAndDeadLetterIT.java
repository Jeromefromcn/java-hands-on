package com.jerome.slledger.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.application.messaging.MessageDecodeException;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.error.CurrencyMismatchException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

/**
 * Which failures are retried, how often and how long apart, and what reaches the dead-letter topic.
 * The listener is scripted by the record's value; every attempt is timestamped.
 */
@Timeout(180)
class RetryAndDeadLetterIT {

  private static final ErrorHandling.Retry RETRY =
      new ErrorHandling.Retry(3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(1));
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static KafkaTemplate<String, String> template;

  private String topic;
  private final Map<String, List<Long>> attempts = new ConcurrentHashMap<>();
  private final List<String> handled = new CopyOnWriteArrayList<>();
  private final Map<String, Integer> failuresLeft = new HashMap<>();
  private ConcurrentMessageListenerContainer<String, String> container;
  private CountDownLatch sentinelHandled;

  @BeforeAll
  static void start() {
    SharedKafka.start();
    template =
        KafkaProducers.template(
            SharedKafka.bootstrapServers(), Duration.ofSeconds(10), Duration.ofSeconds(10));
  }

  @AfterAll
  static void finish() {
    template.destroy();
    Evidence.write("10-retry-dlt.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void setUp() {
    topic = SharedKafka.uniqueTopic("retry");
    SharedKafka.createTopic(topic, 1);
    SharedKafka.createTopic(topic + ".DLT", 1);
    sentinelHandled = new CountDownLatch(1);
    MessageListener<String, String> listener =
        record -> {
          String value = record.value();
          attempts
              .computeIfAbsent(value, v -> Collections.synchronizedList(new ArrayList<>()))
              .add(System.nanoTime());
          switch (value) {
            case "poison" -> throw new MessageDecodeException("not JSON");
            case "wrong-currency" ->
                throw new CurrencyMismatchException(
                    Currency.getInstance("HKD"), Currency.getInstance("USD"));
            case "unknown-security" ->
                throw UnknownReferenceException.subject("security", "HK0000000999");
            case "always-fails" -> throw new QueryTimeoutException("database timed out");
            case "flaky" -> {
              int left = failuresLeft.merge("flaky", -1, Integer::sum);
              if (left >= 0) {
                throw new QueryTimeoutException("database timed out, " + left + " more to go");
              }
              handled.add(value);
            }
            case "sentinel" -> {
              handled.add(value);
              sentinelHandled.countDown();
            }
            default -> handled.add(value);
          }
        };
    container =
        KafkaConsumers.container(
            KafkaConsumers.Settings.of(
                SharedKafka.bootstrapServers(), "retry-" + UUID.randomUUID()),
            topic,
            listener,
            ErrorHandling.build(template, RETRY));
    container.start();
  }

  @AfterEach
  void tearDown() {
    container.stop();
  }

  private void send(String... values) throws Exception {
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(SharedKafka.producerProperties())) {
      for (String value : values) {
        producer.send(new ProducerRecord<>(topic, "K", value)).get();
      }
    }
  }

  private void awaitSentinel() throws Exception {
    send("sentinel");
    assertThat(sentinelHandled.await(90, TimeUnit.SECONDS)).isTrue();
  }

  private List<ConsumerRecord<String, String>> deadLetters() {
    return SharedKafka.readAll(topic + ".DLT");
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  /** Spring writes the original offset as an 8-byte long, not as text. */
  private static long originalOffset(ConsumerRecord<String, String> record) {
    return java.nio.ByteBuffer.wrap(
            record.headers().lastHeader("kafka_dlt-original-offset").value())
        .getLong();
  }

  private static List<Long> gapsMs(List<Long> times) {
    List<Long> gaps = new ArrayList<>();
    for (int i = 1; i < times.size(); i++) {
      gaps.add(TimeUnit.NANOSECONDS.toMillis(times.get(i) - times.get(i - 1)));
    }
    return gaps;
  }

  @Test
  @DisplayName(
      "a message that can never succeed is dead-lettered after one attempt, with the reason in its headers")
  void poisonGoesStraightToTheDeadLetterTopic() throws Exception {
    send("poison", "wrong-currency", "unknown-security");
    awaitSentinel();

    List<ConsumerRecord<String, String>> dead = deadLetters();
    assertThat(dead)
        .extracting(ConsumerRecord::value)
        .containsExactly("poison", "wrong-currency", "unknown-security");
    assertThat(attempts.get("poison")).hasSize(1);
    assertThat(attempts.get("wrong-currency")).hasSize(1);
    assertThat(attempts.get("unknown-security")).hasSize(1);
    assertThat(header(dead.getFirst(), "kafka_dlt-exception-cause-fqcn"))
        .contains("MessageDecodeException");
    assertThat(header(dead.getFirst(), "kafka_dlt-original-topic")).isEqualTo(topic);
    assertThat(originalOffset(dead.getFirst())).isZero();
    EVIDENCE
        .append(
            "non-retryable (decode failure, currency mismatch, unknown security): attempts=1 each, 0 retries, all 3 on ")
        .append(topic.substring(0, 12))
        .append("*.DLT with kafka_dlt-exception-cause-fqcn=")
        .append(header(dead.getFirst(), "kafka_dlt-exception-cause-fqcn"))
        .append(" on the first\n");
  }

  @Test
  @DisplayName(
      "a transient failure is retried with growing pauses and succeeds without a dead letter")
  void transientFailureRecovers() throws Exception {
    failuresLeft.put("flaky", 2);
    send("flaky");
    awaitSentinel();

    assertThat(attempts.get("flaky")).hasSize(3);
    assertThat(handled).contains("flaky");
    assertThat(deadLetters()).isEmpty();
    List<Long> gaps = gapsMs(attempts.get("flaky"));
    assertThat(gaps.get(0)).isGreaterThanOrEqualTo(90);
    assertThat(gaps.get(1)).isGreaterThanOrEqualTo(190);
    EVIDENCE
        .append(
            "transient failure twice then success: attempts=3 (2 retries), gaps between attempts=")
        .append(gaps)
        .append(
            " ms (configured pauses: 100, 200 ms; the second was not honoured, see the note in the decision record), dead letters=0\n");
  }

  @Test
  @DisplayName(
      "a failure that never clears is retried the bounded number of times, then dead-lettered")
  void exhaustedRetriesAreDeadLettered() throws Exception {
    send("always-fails");
    awaitSentinel();

    List<Long> times = attempts.get("always-fails");
    assertThat(times).hasSize(RETRY.maxRetries() + 1);
    List<ConsumerRecord<String, String>> dead = deadLetters();
    assertThat(dead).extracting(ConsumerRecord::value).containsExactly("always-fails");
    assertThat(header(dead.getFirst(), "kafka_dlt-exception-cause-fqcn"))
        .contains("QueryTimeoutException");
    long totalMs = TimeUnit.NANOSECONDS.toMillis(times.getLast() - times.getFirst());
    EVIDENCE
        .append("transient failure that never clears: attempts=")
        .append(times.size())
        .append(" (1 + ")
        .append(RETRY.maxRetries())
        .append(" retries), gaps=")
        .append(gapsMs(times))
        .append(" ms, ")
        .append(totalMs)
        .append(
            " ms from first attempt to last (configured pauses 100, 200, 400 ms = 700 ms), then 1 record on the DLT\n");
  }

  @Test
  @DisplayName("a dead-lettered message does not stop the records behind it")
  void deadLetterDoesNotBlockThePartition() throws Exception {
    send("poison", "ok-1", "ok-2");
    awaitSentinel();

    assertThat(handled).containsSubsequence("ok-1", "ok-2", "sentinel");
    assertThat(deadLetters()).extracting(ConsumerRecord::value).containsExactly("poison");
    EVIDENCE.append(
        "poison, ok-1, ok-2 on one partition: poison dead-lettered, ok-1 and ok-2 handled in order behind it\n");
  }
}
