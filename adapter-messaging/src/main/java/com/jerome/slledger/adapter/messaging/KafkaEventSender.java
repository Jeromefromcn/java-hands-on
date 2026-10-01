package com.jerome.slledger.adapter.messaging;

import com.jerome.slledger.application.messaging.EventSendException;
import com.jerome.slledger.application.messaging.EventSender;
import com.jerome.slledger.application.messaging.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends one message and waits for the broker's ack. The id and the type travel as headers, so a
 * consumer can deduplicate and pick a decoder without parsing the payload first. The trace context,
 * when the message has one, travels as the standard {@code traceparent} header.
 */
public final class KafkaEventSender implements EventSender {

  public static final String MESSAGE_ID = "messageId";
  public static final String EVENT_TYPE = "eventType";

  /** The W3C trace context of the span that wrote the event; absent when it had none. */
  public static final String TRACEPARENT = "traceparent";

  private final KafkaTemplate<String, String> kafka;
  private final Duration ackTimeout;

  /**
   * @param ackTimeout how long {@link #send} waits for the future; set a little above the
   *     producer's delivery timeout, so the producer's own failure is what is reported
   */
  public KafkaEventSender(KafkaTemplate<String, String> kafka, Duration ackTimeout) {
    this.kafka = kafka;
    this.ackTimeout = ackTimeout;
  }

  @Override
  public void send(OutboxMessage message) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(message.topic(), message.key(), message.payload());
    record
        .headers()
        .add(MESSAGE_ID, message.id().toString().getBytes(StandardCharsets.UTF_8))
        .add(EVENT_TYPE, message.type().getBytes(StandardCharsets.UTF_8));
    if (message.traceparent() != null) {
      record.headers().add(TRACEPARENT, message.traceparent().getBytes(StandardCharsets.UTF_8));
    }
    try {
      kafka.send(record).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EventSendException("interrupted while sending " + message.id(), e);
    } catch (ExecutionException | TimeoutException e) {
      throw new EventSendException("no ack for " + message.id() + " on " + message.topic(), e);
    } catch (RuntimeException e) {
      // send() itself throws when metadata cannot be fetched within max.block.ms.
      throw new EventSendException("could not send " + message.id(), e);
    }
  }
}
