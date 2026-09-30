package com.jerome.slledger.application.messaging;

import java.time.Instant;
import java.util.UUID;

/** The ids of the messages each consumer has already acted on. */
public interface ConsumedMessages {

  /**
   * Records that {@code consumer} is acting on {@code messageId}.
   *
   * @return {@code true} if this is the first time; {@code false} if the id was already recorded
   * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction:
   *     a guard row that commits on its own outlives an effect that then fails, and the redelivery
   *     would be discarded
   */
  boolean firstTime(String consumer, UUID messageId, Instant at);

  /** Forgets every id recorded before {@code cutoff}; returns how many. */
  int purgeBefore(Instant cutoff);

  long count();
}
