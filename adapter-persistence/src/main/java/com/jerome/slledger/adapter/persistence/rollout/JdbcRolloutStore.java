package com.jerome.slledger.adapter.persistence.rollout;

import com.jerome.slledger.application.rollout.Rollout;
import com.jerome.slledger.application.rollout.RolloutChange;
import com.jerome.slledger.application.rollout.RolloutStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Rollouts and their changes as plain SQL, on the calling transaction's connection. {@link #move}
 * is a compare-and-set on the version, so two approvals of different raises cannot both apply.
 */
@Repository
public class JdbcRolloutStore implements RolloutStore {

  private static final String CHANGE_COLUMNS =
      "id, flag, from_percent, to_percent, proposed_by, proposed_at, status, decided_by,"
          + " decided_at";

  private final JdbcClient jdbc;

  JdbcRolloutStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Rollout> find(String flag) {
    return jdbc.sql(
            "SELECT flag, percent, effective_since, version FROM feature_rollout WHERE flag = ?")
        .param(flag)
        .query(
            (rs, n) ->
                new Rollout(
                    rs.getString("flag"),
                    rs.getInt("percent"),
                    rs.getTimestamp("effective_since").toInstant(),
                    rs.getLong("version")))
        .optional();
  }

  @Override
  public RolloutChange propose(String flag, int fromPercent, int toPercent, String by, Instant at) {
    long id =
        jdbc.sql(
                "INSERT INTO feature_rollout_change (flag, from_percent, to_percent, proposed_by,"
                    + " proposed_at, status) VALUES (?, ?, ?, ?, ?, 'PROPOSED') RETURNING id")
            .params(flag, fromPercent, toPercent, by, Timestamp.from(at))
            .query(Long.class)
            .single();
    return change(flag, id).orElseThrow();
  }

  @Override
  public Optional<RolloutChange> change(String flag, long id) {
    return jdbc.sql(
            "SELECT " + CHANGE_COLUMNS + " FROM feature_rollout_change WHERE flag = ? AND id = ?")
        .params(flag, id)
        .query((rs, n) -> change(rs))
        .optional();
  }

  @Override
  public boolean move(Rollout current, int percent, Instant at) {
    return jdbc.sql(
                "UPDATE feature_rollout SET percent = ?, effective_since = ?, version = version + 1"
                    + " WHERE flag = ? AND version = ?")
            .params(percent, Timestamp.from(at), current.flag(), current.version())
            .update()
        == 1;
  }

  @Override
  public void applied(long changeId, String by, Instant at) {
    jdbc.sql(
            "UPDATE feature_rollout_change SET status = 'APPLIED', decided_by = ?, decided_at = ?"
                + " WHERE id = ? AND status = 'PROPOSED'")
        .params(by, Timestamp.from(at), changeId)
        .update();
  }

  @Override
  public void supersedeOpen(String flag, Instant at) {
    jdbc.sql(
            "UPDATE feature_rollout_change SET status = 'SUPERSEDED', decided_at = ?"
                + " WHERE flag = ? AND status = 'PROPOSED'")
        .params(Timestamp.from(at), flag)
        .update();
  }

  @Override
  public List<RolloutChange> changes(String flag, int limit) {
    return jdbc.sql(
            "SELECT "
                + CHANGE_COLUMNS
                + " FROM feature_rollout_change WHERE flag = ? ORDER BY id DESC LIMIT ?")
        .params(flag, limit)
        .query((rs, n) -> change(rs))
        .list();
  }

  private static RolloutChange change(ResultSet rs) throws SQLException {
    Timestamp decidedAt = rs.getTimestamp("decided_at");
    return new RolloutChange(
        rs.getLong("id"),
        rs.getString("flag"),
        rs.getInt("from_percent"),
        rs.getInt("to_percent"),
        rs.getString("proposed_by"),
        rs.getTimestamp("proposed_at").toInstant(),
        RolloutChange.Status.valueOf(rs.getString("status")),
        Optional.ofNullable(rs.getString("decided_by")),
        Optional.ofNullable(decidedAt).map(Timestamp::toInstant));
  }
}
