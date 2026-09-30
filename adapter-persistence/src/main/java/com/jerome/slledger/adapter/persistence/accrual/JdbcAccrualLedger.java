package com.jerome.slledger.adapter.persistence.accrual;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.accrual.AccrualLedger;
import com.jerome.slledger.application.accrual.BookResult;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Multi-row {@code INSERT ... ON CONFLICT DO NOTHING} on the primary key {@code (position_id,
 * business_date)}. The row count the statement returns is the number of rows inserted; the rest
 * were already there. One statement per group of rows keeps the number of bind parameters under the
 * driver's limit for any chunk size.
 *
 * <p>Refuses to run outside a transaction: a chunk that is half written is what a restart must not
 * find.
 */
@Repository
public class JdbcAccrualLedger implements AccrualLedger {

  private static final int ROWS_PER_STATEMENT = 1_000;

  private final JdbcClient jdbc;

  JdbcAccrualLedger(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public BookResult book(List<AccrualEntry> entries) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "Accrual entries must be booked inside the transaction of their chunk");
    }
    int inserted = 0;
    for (int from = 0; from < entries.size(); from += ROWS_PER_STATEMENT) {
      List<AccrualEntry> group =
          entries.subList(from, Math.min(entries.size(), from + ROWS_PER_STATEMENT));
      StringBuilder sql =
          new StringBuilder(
              "INSERT INTO accrual_entry (position_id, business_date, fee_amount, fee_currency)"
                  + " VALUES ");
      Object[] params = new Object[group.size() * 4];
      for (int i = 0; i < group.size(); i++) {
        AccrualEntry entry = group.get(i);
        sql.append(i == 0 ? "(?, ?, ?, ?)" : ", (?, ?, ?, ?)");
        params[i * 4] = entry.position().value();
        params[i * 4 + 1] = entry.accruedOn();
        params[i * 4 + 2] = MoneyColumns.amountOf(entry.fee());
        params[i * 4 + 3] = MoneyColumns.currencyOf(entry.fee());
      }
      sql.append(" ON CONFLICT (position_id, business_date) DO NOTHING");
      inserted += jdbc.sql(sql.toString()).params(params).update();
    }
    return new BookResult(inserted, entries.size() - inserted);
  }
}
