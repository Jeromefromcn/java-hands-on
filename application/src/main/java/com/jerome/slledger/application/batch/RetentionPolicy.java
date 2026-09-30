package com.jerome.slledger.application.batch;

import java.time.Duration;

/**
 * How long each kind of finished row is kept. Every figure is a rule with a reason, not a
 * preference.
 *
 * @param outbox published outbox rows: only there for diagnosis once sent, so as long as someone
 *     would look back
 * @param tombstones TCC rows in a terminal state. A cancelled row is what refuses a Try that
 *     arrives late, so it must outlive any delay a Try can have: longer than the topic retention,
 *     the longest a message can be redelivered
 * @param sagas finished sagas' log entries and reservations, kept for audit
 * @param batchSize rows deleted per transaction; a purge of millions is many short transactions,
 *     not one that holds locks and bloats the log
 */
public record RetentionPolicy(Duration outbox, Duration tombstones, Duration sagas, int batchSize) {

  public RetentionPolicy {
    for (Duration d : new Duration[] {outbox, tombstones, sagas}) {
      if (d.isNegative() || d.isZero()) {
        throw new IllegalArgumentException("a retention must be positive, was " + d);
      }
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive, was " + batchSize);
    }
  }
}
