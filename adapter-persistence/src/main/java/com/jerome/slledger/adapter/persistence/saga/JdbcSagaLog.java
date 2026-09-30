package com.jerome.slledger.adapter.persistence.saga;

import com.jerome.slledger.application.saga.SagaLog;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The saga's log. Each entry is one autocommitted statement. It refuses to run inside a
 * transaction, because an entry that is rolled back with the step it describes records nothing, and
 * "what did the saga get to" is the question the log exists to answer.
 */
@Repository
public class JdbcSagaLog implements SagaLog {

  private final JdbcClient jdbc;

  JdbcSagaLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(String sagaId, String step, Phase phase, Outcome outcome, String detail) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A saga log entry must be written outside any transaction, or it rolls back with the step");
    }
    jdbc.sql(
            "INSERT INTO saga_log (saga_id, seq, step, phase, outcome, detail, at)"
                + " SELECT ?, coalesce(max(seq), 0) + 1, ?, ?, ?, ?, ? FROM saga_log WHERE saga_id = ?")
        .params(
            sagaId,
            step,
            phase.name(),
            outcome.name(),
            detail,
            Timestamp.from(Instant.now()),
            sagaId)
        .update();
  }

  @Override
  public List<Entry> read(String sagaId) {
    return jdbc.sql(
            "SELECT seq, step, phase, outcome, detail FROM saga_log WHERE saga_id = ? ORDER BY seq")
        .param(sagaId)
        .query(
            (rs, row) ->
                new Entry(
                    rs.getInt("seq"),
                    rs.getString("step"),
                    Phase.valueOf(rs.getString("phase")),
                    Outcome.valueOf(rs.getString("outcome")),
                    rs.getString("detail")))
        .list();
  }

  @Override
  public List<String> unfinished() {
    return jdbc.sql(
            "SELECT DISTINCT s.saga_id FROM saga_log s WHERE NOT EXISTS (SELECT 1 FROM saga_log t"
                + " WHERE t.saga_id = s.saga_id AND t.step = 'saga' AND t.outcome = 'DONE')"
                + " ORDER BY s.saga_id")
        .query(String.class)
        .list();
  }
}
