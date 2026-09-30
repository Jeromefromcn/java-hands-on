package com.jerome.slledger.adapter.persistence.collateral;

import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Try, Confirm and Cancel with no record of which transaction did what: each operation just moves
 * the amount it is told to. Test-only; it exists to be broken. It works on {@code naive_account}, a
 * table shaped like {@code collateral_account} with no CHECK constraints, so that the wrong numbers
 * can be read instead of being turned into an exception.
 *
 * <p>{@code guardedCancel} is the patch people reach for after the first failure: a Cancel that
 * finds less frozen than it was asked to release does nothing.
 */
final class NaiveCollateralHolds {

  private final JdbcClient jdbc;
  private final boolean guardedCancel;

  NaiveCollateralHolds(JdbcClient jdbc, boolean guardedCancel) {
    this.jdbc = jdbc;
    this.guardedCancel = guardedCancel;
  }

  void tryFreeze(String account, BigDecimal amount) {
    jdbc.sql(
            "UPDATE naive_account SET available = available - ?, frozen = frozen + ?"
                + " WHERE id = ? AND available >= ?")
        .params(amount, amount, account, amount)
        .update();
  }

  void confirm(String account, BigDecimal amount) {
    jdbc.sql("UPDATE naive_account SET frozen = frozen - ? WHERE id = ?")
        .params(amount, account)
        .update();
  }

  void cancel(String account, BigDecimal amount) {
    jdbc.sql(
            "UPDATE naive_account SET available = available + ?, frozen = frozen - ?"
                + " WHERE id = ?"
                + (guardedCancel ? " AND frozen >= ?" : ""))
        .params(
            guardedCancel
                ? new Object[] {amount, amount, account, amount}
                : new Object[] {amount, amount, account})
        .update();
  }
}
