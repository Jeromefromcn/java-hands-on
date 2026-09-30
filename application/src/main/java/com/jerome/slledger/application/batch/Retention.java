package com.jerome.slledger.application.batch;

import java.time.Instant;

/**
 * Deletes finished rows older than a cutoff, at most {@code limit} at a time. Each method is one
 * bounded delete in the caller's transaction and returns how many rows it removed, so a caller that
 * gets fewer than {@code limit} knows it is done.
 */
public interface Retention {

  /** Outbox rows that were published before {@code cutoff}. Unpublished rows are never removed. */
  int purgeOutbox(Instant cutoff, int limit);

  /** TCC rows that are confirmed or cancelled and last changed before {@code cutoff}. */
  int purgeTombstones(Instant cutoff, int limit);

  /**
   * Sagas whose terminal log entry is older than {@code cutoff}: their log entries and their spent
   * reservations. A saga that has not finished is never removed.
   *
   * @return how many sagas were removed
   */
  int purgeFinishedSagas(Instant cutoff, int limit);
}
