package com.jerome.slledger.adapter.persistence.reconciliation;

import com.jerome.slledger.application.reconciliation.SettlementBreaks;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Claims the next due settlement retry with {@code FOR UPDATE SKIP LOCKED}, as the outbox relay
 * claims rows: two instances running the ladder take different breaks and neither waits.
 */
@Repository
public class JdbcSettlementBreaks implements SettlementBreaks {

  private final JdbcClient jdbc;

  JdbcSettlementBreaks(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A settlement attempt claims its break inside the transaction that records the outcome");
    }
  }

  @Override
  public Optional<Due> claimNextDue(Instant now) {
    requireTransaction();
    return jdbc.sql(
            "SELECT snapshot_id, position_id, attempts FROM recon_break"
                + " WHERE disposition = 'RETRY_SETTLEMENT' AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at, snapshot_id, position_id LIMIT 1"
                + " FOR UPDATE SKIP LOCKED")
        .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .query((rs, n) -> new Due(rs.getString(1), rs.getString(2), rs.getInt(3)))
        .optional();
  }

  @Override
  public void resolve(String snapshot, String positionId, int attempts, Instant at) {
    jdbc.sql(
            "UPDATE recon_break SET disposition = 'RESOLVED', attempts = ?, resolved_at = ?,"
                + " next_attempt_at = NULL, detail = detail || ' | settled on attempt ' || ?"
                + " WHERE snapshot_id = ? AND position_id = ?")
        .params(
            attempts, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), attempts, snapshot, positionId)
        .update();
  }

  @Override
  public void scheduleRetry(
      String snapshot, String positionId, int attempts, Instant nextAttemptAt, String lastFailure) {
    jdbc.sql(
            "UPDATE recon_break SET attempts = ?, next_attempt_at = ?,"
                + " detail = detail || ' | attempt ' || ? || ' failed: ' || ?"
                + " WHERE snapshot_id = ? AND position_id = ?")
        .params(
            attempts,
            OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC),
            attempts,
            lastFailure,
            snapshot,
            positionId)
        .update();
  }

  @Override
  public void escalate(String snapshot, String positionId, int attempts, String lastFailure) {
    jdbc.sql(
            "UPDATE recon_break SET disposition = 'ESCALATED', attempts = ?, next_attempt_at = NULL,"
                + " detail = detail || ' | attempt ' || ? || ' failed: ' || ? || ' | escalated'"
                + " WHERE snapshot_id = ? AND position_id = ?")
        .params(attempts, attempts, lastFailure, snapshot, positionId)
        .update();
  }
}
