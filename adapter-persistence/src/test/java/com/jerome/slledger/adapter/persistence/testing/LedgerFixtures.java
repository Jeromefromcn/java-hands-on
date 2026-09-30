package com.jerome.slledger.adapter.persistence.testing;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows inserted straight through SQL, for tests whose subject is not the mappers: volume fixtures,
 * and rows in shapes the mappers would never write. Synthetic throughout: counterparties {@code
 * CP-001…}, ISINs {@code HK00000000n}, HKD at scale 2.
 */
public final class LedgerFixtures {

  private final JdbcClient jdbc;

  public LedgerFixtures(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public LedgerFixtures counterparty(String id) {
    jdbc.sql("INSERT INTO counterparty VALUES (?, ?, 100000000.00, 'HKD')")
        .params(id, "Synthetic Borrower " + id)
        .update();
    return this;
  }

  public LedgerFixtures security(String isin, String price) {
    jdbc.sql("INSERT INTO security VALUES (?, 'Synthetic Equity', 'HKD', ?::numeric, 'HKD')")
        .params(isin, price)
        .update();
    return this;
  }

  /** An OPEN position of {@code quantity}, collateral 1000.00 HKD, flat 3.65%. */
  public LedgerFixtures position(String id, String counterparty, String isin, long quantity) {
    jdbc.sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) VALUES (?, ?, ?, ?, ?, 'OPEN', 1000.00, 'HKD', 'FLAT',"
                + " 0.0365)")
        .params(id, counterparty, isin, quantity, quantity)
        .update();
    return this;
  }

  /**
   * {@code count} OPEN positions {@code prefix0001…}, quantity 1000, in one statement. Ids are
   * zero-padded so their text order is their numeric order.
   */
  public LedgerFixtures positions(String prefix, int count, String counterparty, String isin) {
    jdbc.sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate)"
                + " SELECT ? || lpad(n::text, 4, '0'), ?, ?, 1000, 1000, 'OPEN', 1000.00, 'HKD',"
                + " 'FLAT', 0.0365 FROM generate_series(1, ?) n")
        .params(prefix, counterparty, isin, count)
        .update();
    return this;
  }

  /** One entry per day for {@code days} days from {@code from}, each of {@code fee} HKD. */
  public LedgerFixtures entries(String positionId, LocalDate from, int days, String fee) {
    jdbc.sql(
            "INSERT INTO accrual_entry (position_id, business_date, fee_amount, fee_currency)"
                + " SELECT ?, ?::date + d, ?::numeric, 'HKD' FROM generate_series(0, ? - 1) d")
        .params(positionId, from, fee, days)
        .update();
    return this;
  }

  /** {@link #entries} for every position whose id starts with {@code prefix}, in one statement. */
  public LedgerFixtures entriesForAll(String prefix, LocalDate from, int days, String fee) {
    jdbc.sql(
            "INSERT INTO accrual_entry (position_id, business_date, fee_amount, fee_currency)"
                + " SELECT p.id, ?::date + d, ?::numeric, 'HKD'"
                + " FROM loan_position p, generate_series(0, ? - 1) d WHERE p.id LIKE ? || '%'")
        .params(from, fee, days, prefix)
        .update();
    return this;
  }

  /**
   * A BORROW trade for every position whose id starts with {@code prefix}, settling on {@code
   * settlement}, so the position counts as active from that date. Idempotency keys are {@code "k-"
   * + id}.
   */
  public LedgerFixtures borrowTradesForAll(String prefix, LocalDate settlement) {
    jdbc.sql(
            "INSERT INTO loan_trade (idempotency_key, position_id, counterparty_id, isin,"
                + " direction, quantity, unit_price_amount, unit_price_currency, trade_date,"
                + " settlement_date)"
                + " SELECT 'k-' || id, id, counterparty_id, isin, 'BORROW', original_quantity,"
                + " 41.26, 'HKD', ?::date - 2, ?::date FROM loan_position WHERE id LIKE ? || '%'")
        .params(settlement, settlement, prefix)
        .update();
    return this;
  }

  /**
   * {@code count} OPEN positions {@code prefix000001…} in one statement. {@code counterpartySql} is
   * a SQL expression over {@code n} (the row number) that yields each position's counterparty, so a
   * test can skew the ledger. Test code only: the expression is not bound.
   */
  public LedgerFixtures bulkPositions(
      String prefix, int count, String isin, String counterpartySql) {
    jdbc.sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate)"
                + " SELECT ? || lpad(n::text, 6, '0'), "
                + counterpartySql
                + ", ?, 1000, 1000, 'OPEN', 1000.00, 'HKD', 'FLAT', 0.0365"
                + " FROM generate_series(1, ?) n")
        .params(prefix, isin, count)
        .update();
    return this;
  }
}
