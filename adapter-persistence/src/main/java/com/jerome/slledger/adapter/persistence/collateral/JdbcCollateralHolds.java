package com.jerome.slledger.adapter.persistence.collateral;

import com.jerome.slledger.application.collateral.CollateralHolds;
import com.jerome.slledger.application.collateral.CollateralRefusedException;
import com.jerome.slledger.application.collateral.CollateralRefusedException.Reason;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Try, Confirm and Cancel over {@code collateral_account} and {@code tcc_transaction}.
 *
 * <p>The transaction id's primary key does the work of all three protections. Try and Cancel both
 * start with {@code INSERT ... ON CONFLICT DO NOTHING}: whichever arrives first for an id writes
 * the row, the other finds it. A Cancel that writes the row first has recorded an empty rollback,
 * and the Try that follows is refused. A row that exists is then read {@code FOR UPDATE}, so the
 * status decisions are made against a row nobody else can change until this transaction ends.
 * Confirm and Cancel move money only when they change the status from {@code TRIED}, so a repeat
 * finds another status and moves nothing.
 *
 * <p>A refusal is an exception and leaves the caller's transaction to roll back; nothing here
 * commits or rolls back on its own.
 */
@Repository
public class JdbcCollateralHolds implements CollateralHolds {

  private static final String TRIED = "TRIED";
  private static final String CONFIRMED = "CONFIRMED";
  private static final String CANCELLED = "CANCELLED";

  private record Hold(String status, String counterparty, String currency, BigDecimal amount) {}

  private final JdbcClient jdbc;

  JdbcCollateralHolds(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void tryFreeze(String xid, CounterpartyId counterparty, Money amount) {
    requireTransaction();
    boolean inserted =
        jdbc.sql(
                    "INSERT INTO tcc_transaction (xid, counterparty_id, currency, amount, status,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (xid) DO NOTHING")
                .params(
                    xid,
                    counterparty.value(),
                    amount.currency().getCurrencyCode(),
                    amount.amount(),
                    TRIED,
                    now())
                .update()
            == 1;
    if (!inserted) {
      Hold existing = lock(xid);
      if (CANCELLED.equals(existing.status())) {
        throw new CollateralRefusedException(Reason.ALREADY_CANCELLED, xid);
      }
      return; // TRIED or CONFIRMED: this Try is a repeat
    }
    int moved =
        jdbc.sql(
                "UPDATE collateral_account SET available = available - ?, frozen = frozen + ?"
                    + " WHERE counterparty_id = ? AND currency = ? AND available >= ?")
            .params(
                amount.amount(),
                amount.amount(),
                counterparty.value(),
                amount.currency().getCurrencyCode(),
                amount.amount())
            .update();
    if (moved == 0) {
      boolean accountExists =
          jdbc.sql(
                      "SELECT count(*) FROM collateral_account WHERE counterparty_id = ? AND currency = ?")
                  .params(counterparty.value(), amount.currency().getCurrencyCode())
                  .query(Long.class)
                  .single()
              > 0;
      throw new CollateralRefusedException(
          accountExists ? Reason.INSUFFICIENT_COLLATERAL : Reason.NO_ACCOUNT, xid);
    }
  }

  @Override
  public void confirm(String xid) {
    requireTransaction();
    Hold hold = find(xid);
    if (hold == null) {
      throw new CollateralRefusedException(Reason.NEVER_TRIED, xid);
    }
    switch (hold.status()) {
      case CONFIRMED -> {
        return;
      }
      case CANCELLED -> throw new CollateralRefusedException(Reason.ALREADY_CANCELLED, xid);
      default -> {
        setStatus(xid, CONFIRMED);
        jdbc.sql(
                "UPDATE collateral_account SET frozen = frozen - ? WHERE counterparty_id = ? AND"
                    + " currency = ?")
            .params(hold.amount(), hold.counterparty(), hold.currency())
            .update();
      }
    }
  }

  @Override
  public void cancel(String xid) {
    requireTransaction();
    boolean insertedTombstone =
        jdbc.sql(
                    "INSERT INTO tcc_transaction (xid, status, updated_at) VALUES (?, ?, ?)"
                        + " ON CONFLICT (xid) DO NOTHING")
                .params(xid, CANCELLED, now())
                .update()
            == 1;
    if (insertedTombstone) {
      return; // an empty rollback: nothing was frozen, and now a late Try will be refused
    }
    Hold hold = lock(xid);
    switch (hold.status()) {
      case CANCELLED -> {
        return;
      }
      case CONFIRMED -> throw new CollateralRefusedException(Reason.ALREADY_CONFIRMED, xid);
      default -> {
        setStatus(xid, CANCELLED);
        jdbc.sql(
                "UPDATE collateral_account SET available = available + ?, frozen = frozen - ?"
                    + " WHERE counterparty_id = ? AND currency = ?")
            .params(hold.amount(), hold.amount(), hold.counterparty(), hold.currency())
            .update();
      }
    }
  }

  private Hold find(String xid) {
    return jdbc.sql(
            "SELECT status, counterparty_id, currency, amount FROM tcc_transaction WHERE xid = ?"
                + " FOR UPDATE")
        .param(xid)
        .query(
            (rs, row) ->
                new Hold(
                    rs.getString("status"),
                    rs.getString("counterparty_id"),
                    rs.getString("currency"),
                    rs.getBigDecimal("amount")))
        .optional()
        .orElse(null);
  }

  private Hold lock(String xid) {
    Hold hold = find(xid);
    if (hold == null) {
      throw new IllegalStateException("tcc_transaction " + xid + " vanished under its own lock");
    }
    return hold;
  }

  private void setStatus(String xid, String status) {
    jdbc.sql("UPDATE tcc_transaction SET status = ?, updated_at = ? WHERE xid = ?")
        .params(status, now(), xid)
        .update();
  }

  private static Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A collateral hold must be changed inside the caller's transaction");
    }
  }
}
