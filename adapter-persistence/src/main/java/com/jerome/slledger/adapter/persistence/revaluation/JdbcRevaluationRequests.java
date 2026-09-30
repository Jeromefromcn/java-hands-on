package com.jerome.slledger.adapter.persistence.revaluation;

import com.jerome.slledger.application.revaluation.RevaluationRequests;
import com.jerome.slledger.domain.instrument.Isin;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcRevaluationRequests implements RevaluationRequests {

  private final JdbcClient jdbc;

  JdbcRevaluationRequests(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void request(Isin security, long priceVersion) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A revaluation request belongs in the transaction of the price update that caused it");
    }
    jdbc.sql(
            "INSERT INTO revaluation_request (isin, price_version, status, created_at)"
                + " VALUES (?, ?, 'PENDING', now()) ON CONFLICT DO NOTHING")
        .params(security.value(), priceVersion)
        .update();
  }

  @Override
  public List<Pending> pending(int limit) {
    return jdbc.sql(
            "SELECT isin, price_version, attempts FROM revaluation_request"
                + " WHERE status = 'PENDING' ORDER BY created_at, isin, price_version LIMIT ?")
        .param(limit)
        .query(
            (rs, n) ->
                new Pending(
                    new Isin(rs.getString("isin").trim()),
                    rs.getLong("price_version"),
                    rs.getInt("attempts")))
        .list();
  }

  @Override
  public OptionalLong currentPriceVersion(Isin security) {
    return jdbc.sql("SELECT price_version FROM security WHERE isin = ?")
        .param(security.value())
        .query(Long.class)
        .optional()
        .map(OptionalLong::of)
        .orElseGet(OptionalLong::empty);
  }

  @Override
  public void markDone(Isin security, long priceVersion) {
    finish(security, priceVersion, "DONE");
  }

  @Override
  public void markSuperseded(Isin security, long priceVersion) {
    finish(security, priceVersion, "SUPERSEDED");
  }

  private void finish(Isin security, long priceVersion, String status) {
    jdbc.sql(
            "UPDATE revaluation_request SET status = ?, completed_at = now()"
                + " WHERE isin = ? AND price_version = ? AND status = 'PENDING'")
        .params(status, security.value(), priceVersion)
        .update();
  }

  @Override
  public void recordAttempt(Isin security, long priceVersion, int maxAttempts) {
    jdbc.sql(
            "UPDATE revaluation_request SET attempts = attempts + 1,"
                + " status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE status END"
                + " WHERE isin = ? AND price_version = ?")
        .params(maxAttempts, security.value(), priceVersion)
        .update();
  }
}
