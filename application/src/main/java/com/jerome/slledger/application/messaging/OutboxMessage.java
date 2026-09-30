package com.jerome.slledger.application.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An event in the form that is stored in the outbox and put on the wire: its identity, where it
 * goes, and its payload already encoded. The encoding is decided once, when the event is appended,
 * so what is sent is exactly what was committed.
 */
public record OutboxMessage(
    UUID id, String topic, String key, String type, String payload, Instant createdAt) {

  public OutboxMessage {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  public static OutboxMessage of(IntegrationEvent event, String payload) {
    return new OutboxMessage(
        event.id(), event.topic(), event.key(), event.type(), payload, event.occurredAt());
  }
}
