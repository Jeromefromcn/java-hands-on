package com.jerome.slledger.adapter.persistence.revaluation;

import com.jerome.slledger.adapter.persistence.accrual.JdbcAccrualCandidates;
import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.revaluation.RevaluationCandidates;
import com.jerome.slledger.domain.instrument.Isin;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Keyset pages of one security's open positions, on {@code loan_position (isin, id)}. */
@Repository
public class JdbcRevaluationCandidates implements RevaluationCandidates {

  private static final String FROM =
      " FROM loan_position p JOIN security s ON s.isin = p.isin"
          + " WHERE p.isin = ? AND p.status <> 'CLOSED'";

  private final JdbcClient jdbc;

  JdbcRevaluationCandidates(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AccrualCandidate> page(Isin security, Optional<String> afterId, int limit) {
    String select = "SELECT " + JdbcAccrualCandidates.COLUMNS.substring("SELECT ".length()) + FROM;
    return afterId
        .map(
            after ->
                jdbc.sql(select + " AND p.id > ? ORDER BY p.id LIMIT ?")
                    .params(security.value(), after, limit))
        .orElseGet(
            () -> jdbc.sql(select + " ORDER BY p.id LIMIT ?").params(security.value(), limit))
        .query((rs, n) -> JdbcAccrualCandidates.candidate(rs))
        .list();
  }
}
