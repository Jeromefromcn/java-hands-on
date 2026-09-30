package com.jerome.slledger.adapter.persistence.accrual;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LabDb;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Exercise 8: the monthly fee report's date-range query over 500 positions × 1,000 days = 500,000
 * accrual entries, planned with no index, with the primary key's (position_id, business_date), and
 * with (business_date, position_id). Plans are asserted by scan type, never by duration.
 *
 * <p>Ordered: runs 1 to 3 change the indexes of one throwaway database in turn.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExplainPlanIT {

  static final int POSITIONS = 500;
  static final int DAYS = 1_000;

  /** Twenty days in the middle of the range. */
  static final String REPORT =
      "SELECT position_id, sum(fee_amount) FROM accrual_entry"
          + " WHERE business_date BETWEEN DATE '2025-06-01' AND DATE '2025-06-20'"
          + " GROUP BY position_id";

  private static final StringBuilder EVIDENCE = new StringBuilder();
  private static String throwaway;

  @BeforeAll
  static void fill() throws SQLException {
    throwaway = newDatabase("explain_runs");
    migrate(throwaway, "2");
    try (Connection c = connect(throwaway);
        Statement s = c.createStatement()) {
      fillLedger(c);
      try (var version = s.executeQuery("SHOW server_version")) {
        version.next();
        EVIDENCE.append("Postgres %s%n".formatted(version.getString(1)));
      }
    }
    EVIDENCE.append("Query:%n  %s%n%n".formatted(REPORT));
  }

  @AfterAll
  static void writeEvidence() {
    Evidence.write("06-explain.txt", EVIDENCE.toString());
  }

  @Test
  @Order(1)
  void withNoIndexItIsASequentialScan() throws SQLException {
    try (Connection c = connect(throwaway)) {
      execute(c, "ALTER TABLE accrual_entry DROP CONSTRAINT accrual_entry_pkey");
      execute(c, "ANALYZE accrual_entry");
      List<String> plan = explain(c);

      assertThat(plan).anyMatch(line -> line.contains("Seq Scan on accrual_entry"));
      record("1. no index (primary key dropped on this throwaway database)", plan);
    }
  }

  /**
   * Prediction: still a sequential scan. The leading column is unconstrained, and Postgres 17 has
   * no skip scan.
   */
  @Test
  @Order(2)
  void withThePrimaryKeysOrderItIsStillASequentialScan() throws SQLException {
    try (Connection c = connect(throwaway)) {
      execute(c, "ALTER TABLE accrual_entry ADD PRIMARY KEY (position_id, business_date)");
      execute(c, "ANALYZE accrual_entry");
      List<String> plan = explain(c);

      assertThat(plan).anyMatch(line -> line.contains("Seq Scan on accrual_entry"));
      assertThat(plan).noneMatch(line -> line.contains("accrual_entry_pkey"));
      record("2. primary key (position_id, business_date) only", plan);
    }
  }

  @Test
  @Order(3)
  void withTheDateFirstItUsesTheIndex() throws SQLException {
    try (Connection c = connect(throwaway)) {
      execute(
          c, "CREATE INDEX accrual_entry_date_first ON accrual_entry (business_date, position_id)");
      execute(c, "ANALYZE accrual_entry");
      List<String> plan = explain(c);

      assertThat(plan)
          .anyMatch(
              line ->
                  (line.contains("Index Scan") || line.contains("Bitmap Index Scan"))
                      && line.contains("accrual_entry_date_first"));
      record("3. plus (business_date, position_id)", plan);
    }
  }

  /** The production migrations, V1 to V3, give the same plan through V3's index. */
  @Test
  @Order(4)
  void v3IsTheIndexTheReportUses() throws SQLException {
    String migrated = newDatabase("explain_v3");
    migrate(migrated, null);
    try (Connection c = connect(migrated)) {
      fillLedger(c);
      List<String> plan = explain(c);

      assertThat(plan).anyMatch(line -> line.contains("accrual_entry_business_date_idx"));
      record("4. Flyway V1-V3 on a fresh database", plan);
    }
  }

  // --- infrastructure ---

  private static void fillLedger(Connection c) throws SQLException {
    execute(
        c,
        "INSERT INTO counterparty VALUES ('CP-001', 'Synthetic Borrower CP-001', 100000000.00,"
            + " 'HKD')");
    execute(
        c, "INSERT INTO security VALUES ('HK0000000001', 'Synthetic Equity', 'HKD', 41.26, 'HKD')");
    execute(
        c,
        ("INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate)"
                + " SELECT 'LP-' || lpad(n::text, 4, '0'), 'CP-001', 'HK0000000001', 1000, 1000,"
                + " 'OPEN', 1000.00, 'HKD', 'FLAT', 0.0365 FROM generate_series(1, %d) n")
            .formatted(POSITIONS));
    execute(
        c,
        ("INSERT INTO accrual_entry (position_id, business_date, fee_amount, fee_currency)"
                + " SELECT p.id, DATE '2024-01-01' + d, 0.41, 'HKD'"
                + " FROM loan_position p, generate_series(0, %d - 1) d")
            .formatted(DAYS));
    execute(c, "ANALYZE");
  }

  private static List<String> explain(Connection c) throws SQLException {
    return LabDb.explain(c, REPORT);
  }

  private static void record(String caption, List<String> plan) {
    EVIDENCE.append(caption).append('\n');
    plan.forEach(line -> EVIDENCE.append("  ").append(line).append('\n'));
    EVIDENCE.append('\n');
  }

  private static String newDatabase(String name) {
    return LabDb.create(name);
  }

  private static void migrate(String url, String target) {
    LabDb.migrate(url, target);
  }

  private static Connection connect(String url) throws SQLException {
    return LabDb.connect(url);
  }

  private static void execute(Connection c, String sql) throws SQLException {
    LabDb.execute(c, sql);
  }
}
