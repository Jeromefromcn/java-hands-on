package com.jerome.slledger.adapter.persistence.revaluation;

import com.jerome.slledger.application.revaluation.MarginCallMarkers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@code INSERT ... ON CONFLICT DO NOTHING}: the row count says whether this call was first. */
@Repository
public class JdbcMarginCallMarkers implements MarginCallMarkers {

  private final JdbcClient jdbc;

  JdbcMarginCallMarkers(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean mark(String positionId, long priceVersion) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A margin call marker must be written in the transaction of the outbox row it guards");
    }
    return jdbc.sql(
                "INSERT INTO margin_call_marker (position_id, price_version) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING")
            .params(positionId, priceVersion)
            .update()
        == 1;
  }
}
