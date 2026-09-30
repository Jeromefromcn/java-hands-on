package com.jerome.slledger.bootstrap.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Messaging. Off unless {@code enabled} is true, so a profile that does not ask for it behaves as
 * stage 9 left it: no Kafka bean, no thread, no connection.
 *
 * @param sendTimeout the most a send may take from call to ack, retries included. The relay holds a
 *     database transaction across its sends, so this times {@code relayBatchSize} bounds how long
 *     one pass can keep a connection.
 * @param maxBlock the most a send may block before the broker is even asked (metadata, buffer)
 * @param topicRetention how long the broker keeps a message; the longest a message can be
 *     redelivered
 * @param dedupTtl how long a consumed message id is remembered; must exceed {@code topicRetention}
 */
@Validated
@ConfigurationProperties(prefix = "ledger.messaging")
public record MessagingProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("127.0.0.1:9092") @NotBlank String bootstrapServers,
    @DefaultValue("5s") @NotNull Duration sendTimeout,
    @DefaultValue("2s") @NotNull Duration maxBlock,
    @DefaultValue("50") @Min(1) int relayBatchSize,
    @DefaultValue("500ms") @NotNull Duration relayInterval,
    @DefaultValue("true") boolean relayEnabled,
    @DefaultValue("7d") @NotNull Duration topicRetention,
    @DefaultValue("8d") @NotNull Duration dedupTtl,
    @DefaultValue("1h") @NotNull Duration dedupPurgeInterval) {

  /**
   * A message can be redelivered for as long as the topic still holds it, so an id forgotten before
   * that is an id a duplicate can slip past. The TTL has to be longer than the retention.
   */
  @AssertTrue(message = "dedup-ttl must be longer than topic-retention")
  public boolean isDedupTtlOutlivesRetention() {
    return dedupTtl.compareTo(topicRetention) > 0;
  }
}
