package com.jerome.slledger.adapter.messaging;

import com.jerome.slledger.application.messaging.MessageDecodeException;
import com.jerome.slledger.application.messaging.Topics;
import com.jerome.slledger.application.observability.LedgerMetrics;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.error.DomainException;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens when a listener throws: retry with a growing pause, then set the message aside.
 *
 * <p>The classification is a decision, not a default. A failure is <b>not retried</b> when trying
 * again reads the same bytes and asks the same question: a payload that does not decode ({@link
 * MessageDecodeException}), a refusal by the domain ({@link DomainException}: a price in the wrong
 * currency), a reference to something that does not exist ({@link UnknownReferenceException}). It
 * goes to the dead-letter topic on the first failure, where a person looks at it. Everything else
 * is retried, because the cause may not be in the message: a database that timed out, a lock that
 * was busy, a deployment in progress. A bug that throws {@code NullPointerException} is in that
 * group too, and is retried a bounded number of times before it lands in the dead-letter topic;
 * that costs a few seconds and is the price of not having to predict every transient failure by
 * class.
 *
 * <p>Retries block the partition: the consumer pauses on the failing record and tries again. That
 * is deliberate. A price topic is keyed by ISIN and its order matters; a retry topic would let a
 * later price overtake the one being retried. The pause is bounded by {@link Retry}.
 */
public final class ErrorHandling {

  private ErrorHandling() {}

  /**
   * @param maxRetries retries after the first attempt; a message is tried {@code maxRetries + 1}
   *     times before it is dead-lettered
   */
  public record Retry(
      int maxRetries, Duration initialInterval, double multiplier, Duration maxInterval) {

    /**
     * Four retries at 0.5, 1, 2 and 4 seconds: a message that never clears holds its partition for
     * about seven and a half seconds. Below about half a second the pauses were not honoured in
     * measurement (see {@code 10-retry-dlt.txt}), so the first interval is not set lower.
     */
    public static Retry defaults() {
      return new Retry(4, Duration.ofMillis(500), 2.0, Duration.ofSeconds(5));
    }
  }

  public static DefaultErrorHandler build(KafkaOperations<String, String> kafka, Retry retry) {
    return build(kafka, retry, LedgerMetrics.NONE);
  }

  /** As above, and each dead-lettered message is counted by its source topic. */
  public static DefaultErrorHandler build(
      KafkaOperations<String, String> kafka, Retry retry, LedgerMetrics metrics) {
    DeadLetterPublishingRecoverer publisher =
        new DeadLetterPublishingRecoverer(
            kafka,
            // Partition -1: let the producer place it by key, so the dead-letter topic does not
            // have to have as many partitions as the topic it belongs to.
            (record, failure) -> new TopicPartition(Topics.deadLetterOf(record.topic()), -1));
    ConsumerRecordRecoverer recoverer =
        (record, failure) -> {
          publisher.accept(record, failure);
          metrics.deadLettered(record.topic());
        };

    ExponentialBackOff backOff =
        new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
    backOff.setMaxInterval(retry.maxInterval().toMillis());
    backOff.setMaxAttempts(retry.maxRetries());

    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        MessageDecodeException.class, DomainException.class, UnknownReferenceException.class);
    return handler;
  }
}
