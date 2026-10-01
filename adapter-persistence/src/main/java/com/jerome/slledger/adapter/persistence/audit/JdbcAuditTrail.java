package com.jerome.slledger.adapter.persistence.audit;

import com.jerome.slledger.application.audit.AuditEvent;
import com.jerome.slledger.application.audit.AuditQuery;
import com.jerome.slledger.application.audit.AuditStore;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The audit table as plain SQL. {@link #append} runs on whatever connection the calling transaction
 * holds, which is what lets a booking's line commit and roll back with the booking; whether that
 * transaction is the booking's own or a new one is decided by the caller, not here. Every value is
 * a bind parameter.
 */
@Repository
public class JdbcAuditTrail implements AuditStore, AuditQuery {

  static final String INSERT =
      "INSERT INTO audit_event (occurred_at, actor, action, subject, subject_id, result, reason)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?)";

  private static final String COLUMNS =
      "occurred_at, actor, action, subject, subject_id, result, reason";

  private final JdbcClient jdbc;

  JdbcAuditTrail(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(AuditEvent event) {
    jdbc.sql(INSERT)
        .params(
            Timestamp.from(event.at()),
            event.actor(),
            event.action().name(),
            event.subject().name(),
            event.subjectId(),
            event.result().name(),
            event.reason())
        .update();
  }

  @Override
  public List<AuditEvent> recent(Optional<String> subjectId, int limit) {
    JdbcClient.StatementSpec statement =
        subjectId
            .map(
                id ->
                    jdbc.sql(
                            "SELECT "
                                + COLUMNS
                                + " FROM audit_event WHERE subject_id = ? ORDER BY id DESC LIMIT ?")
                        .params(id, limit))
            .orElseGet(
                () ->
                    jdbc.sql("SELECT " + COLUMNS + " FROM audit_event ORDER BY id DESC LIMIT ?")
                        .param(limit));
    return statement
        .query(
            (rs, n) ->
                new AuditEvent(
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("actor"),
                    AuditEvent.Action.valueOf(rs.getString("action")),
                    AuditEvent.Subject.valueOf(rs.getString("subject")),
                    rs.getString("subject_id"),
                    AuditEvent.Result.valueOf(rs.getString("result")),
                    rs.getString("reason")))
        .list();
  }
}
