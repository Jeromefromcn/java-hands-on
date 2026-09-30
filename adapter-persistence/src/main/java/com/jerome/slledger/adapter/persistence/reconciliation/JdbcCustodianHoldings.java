package com.jerome.slledger.adapter.persistence.reconciliation;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.reconciliation.CustodianHolding;
import com.jerome.slledger.application.reconciliation.CustodianHoldings;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Multi-row upsert of a chunk of the snapshot. */
@Repository
public class JdbcCustodianHoldings implements CustodianHoldings {

  private static final int ROWS_PER_STATEMENT = 1_000;

  private final JdbcClient jdbc;

  JdbcCustodianHoldings(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(String snapshot, List<CustodianHolding> holdings) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A chunk of a snapshot must be loaded inside the transaction of its chunk");
    }
    for (int from = 0; from < holdings.size(); from += ROWS_PER_STATEMENT) {
      List<CustodianHolding> group =
          holdings.subList(from, Math.min(holdings.size(), from + ROWS_PER_STATEMENT));
      StringBuilder sql =
          new StringBuilder(
              "INSERT INTO custodian_holding (snapshot_id, position_id, quantity, value_amount,"
                  + " value_currency) VALUES ");
      Object[] params = new Object[group.size() * 5];
      for (int i = 0; i < group.size(); i++) {
        CustodianHolding holding = group.get(i);
        sql.append(i == 0 ? "(?, ?, ?, ?, ?)" : ", (?, ?, ?, ?, ?)");
        params[i * 5] = snapshot;
        params[i * 5 + 1] = holding.positionId();
        params[i * 5 + 2] = holding.quantity();
        params[i * 5 + 3] = MoneyColumns.amountOf(holding.value());
        params[i * 5 + 4] = MoneyColumns.currencyOf(holding.value());
      }
      sql.append(
          " ON CONFLICT (snapshot_id, position_id) DO UPDATE SET quantity = EXCLUDED.quantity,"
              + " value_amount = EXCLUDED.value_amount, value_currency = EXCLUDED.value_currency");
      jdbc.sql(sql.toString()).params(params).update();
    }
  }
}
