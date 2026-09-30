package com.jerome.slledger.application.messaging;

import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs an effect at most once per message id, per consumer.
 *
 * <p>The guard row and the effect share a transaction. If the effect throws, both roll back, and a
 * redelivery finds no row and runs it again; if the process dies after the effect and before the
 * offset is committed, the redelivery finds the row and does nothing. The one thing the transaction
 * cannot cover is an effect outside the database: a notice already sent when the transaction then
 * rolls back is sent again on redelivery. That is at-least-once, and the notice channel is where it
 * is stated.
 */
public class IdempotentConsumer {

  private final ConsumedMessages consumed;
  private final Clock clock;

  public IdempotentConsumer(ConsumedMessages consumed, Clock clock) {
    this.consumed = consumed;
    this.clock = clock;
  }

  /**
   * @return {@code true} if the effect ran, {@code false} if this message had been acted on
   */
  @Transactional
  public boolean once(String consumer, UUID messageId, Runnable effect) {
    if (!consumed.firstTime(consumer, messageId, clock.instant())) {
      return false;
    }
    effect.run();
    return true;
  }
}
