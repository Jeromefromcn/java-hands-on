package com.jerome.slledger.adapter.persistence.retention;

import com.jerome.slledger.application.batch.Retention;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded deletes by key: each picks at most {@code limit} keys in a subquery and deletes exactly
 * those, so a purge of a large table is many short transactions.
 */
@Repository
public class JdbcRetention implements Retention {

  private final JdbcClient jdbc;

  JdbcRetention(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A purge batch must run inside a transaction of its own");
    }
  }

  @Override
  public int purgeOutbox(Instant cutoff, int limit) {
    requireTransaction();
    return jdbc.sql(
            "DELETE FROM outbox_event WHERE id IN (SELECT id FROM outbox_event"
                + " WHERE published_at IS NOT NULL AND published_at < ? ORDER BY seq LIMIT ?)")
        .params(at(cutoff), limit)
        .update();
  }

  @Override
  public int purgeTombstones(Instant cutoff, int limit) {
    requireTransaction();
    return jdbc.sql(
            "DELETE FROM tcc_transaction WHERE xid IN (SELECT xid FROM tcc_transaction"
                + " WHERE status IN ('CONFIRMED', 'CANCELLED') AND updated_at < ? LIMIT ?)")
        .params(at(cutoff), limit)
        .update();
  }

  @Override
  public int purgeFinishedSagas(Instant cutoff, int limit) {
    requireTransaction();
    // One statement: choose the sagas, delete their spent reservations and their log, count them.
    return jdbc.sql(
            "WITH done AS (SELECT saga_id FROM saga_log WHERE step = 'saga' AND outcome = 'DONE'"
                + " AND at < ? ORDER BY saga_id LIMIT ?),"
                + " reservations AS (DELETE FROM credit_reservation WHERE saga_id IN"
                + " (SELECT saga_id FROM done) AND status IN ('CONSUMED', 'RELEASED') RETURNING 1),"
                + " entries AS (DELETE FROM saga_log WHERE saga_id IN (SELECT saga_id FROM done)"
                + " RETURNING 1) SELECT count(*) FROM done")
        .params(at(cutoff), limit)
        .query(Integer.class)
        .single();
  }
}
