package com.jerome.slledger.adapter.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Exercise 1: a column type change under blue/green, done in one step and then as expand/contract.
 *
 * <p>The migrations under {@code db/lab/bluegreen} are test-only and never on the production Flyway
 * path. Two writers stand for the two application versions live during a deploy: the old one binds
 * the key with {@code setString}, as the current {@code loan_trade.idempotency_key text} mapping
 * does; the new one binds a {@link UUID}.
 */
class BlueGreenMigrationIT {

  private static final AtomicInteger DATABASES = new AtomicInteger();
  private static final StringBuilder EVIDENCE = new StringBuilder();

  @AfterAll
  static void writeEvidence() {
    Evidence.write("06-blue-green.txt", EVIDENCE.toString());
  }

  /**
   * The single-step {@code ALTER COLUMN … TYPE uuid}. With an old-style key already stored, the
   * migration itself fails; on an empty table it succeeds, and then the old version, still serving
   * half the traffic, cannot insert at all. See docs/decisions/06-persistence.md.
   */
  @Test
  void reproducesBlueGreenBreakageOnColumnTypeChange() throws SQLException {
    String withOldRow = newDatabase();
    migrate(withOldRow, "2", "breaking");
    try (Connection c = connect(withOldRow)) {
      referenceRows(c);
      oldWriter(c, "req-42");
    }
    assertThatThrownBy(() -> migrate(withOldRow, null, "breaking"))
        .isInstanceOf(FlywayException.class)
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("22P02"));
    record(
        "breaking, table holds 'req-42'",
        "migrate V1000",
        sqlFailure(() -> migrate(withOldRow, null, "breaking")));

    String empty = newDatabase();
    migrate(empty, null, "breaking");
    try (Connection c = connect(empty)) {
      referenceRows(c);
      assertThatThrownBy(() -> oldWriter(c, "req-42"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42804"));
      record(
          "breaking, empty table, migrated",
          "old writer 'req-42'",
          sqlFailure(() -> oldWriter(c, "req-43")));
      // Not the key's shape: the bind type. A perfectly valid UUID sent through setString fails
      // too.
      String uuidAsText = UUID.randomUUID().toString();
      assertThatThrownBy(() -> oldWriter(c, uuidAsText))
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42804"));
      record(
          "breaking, empty table, migrated",
          "old writer, UUID-shaped text",
          sqlFailure(() -> oldWriter(c, UUID.randomUUID().toString())));
      newWriterAfterBreaking(c, UUID.randomUUID());
      record("breaking, empty table, migrated", "new writer", "ok");
    }
  }

  @Test
  void expandContractKeepsBothVersionsWorking() throws SQLException {
    String db = newDatabase();
    migrate(db, "2", "expand");
    try (Connection c = connect(db)) {
      referenceRows(c);
      oldWriter(c, "req-before-expand");
    }

    migrate(db, "1001", "expand");
    try (Connection c = connect(db)) {
      oldWriter(c, "req-after-1001");
      newWriter(c, UUID.randomUUID());
      record("after V1001 (add column)", "old + new writer", "ok");
      // The old writer leaves the new column empty until V1002's trigger exists.
      assertThat(nullUuids(c)).isEqualTo(2);
    }

    migrate(db, "1002", "expand");
    try (Connection c = connect(db)) {
      oldWriter(c, "req-after-1002");
      newWriter(c, UUID.randomUUID());
      record("after V1002 (dual write + backfill)", "old + new writer", "ok");
      assertThat(nullUuids(c)).isZero();
      // Backfilled rows, trigger-filled rows and the new writer's rows all agree with one rule, so
      // a key resubmitted after the text column is gone still lands on the uuid it had before.
      assertThat(
              single(
                  c,
                  "SELECT count(*) FROM loan_trade"
                      + " WHERE idempotency_uuid <> idempotency_uuid_of(idempotency_key)"))
          .isZero();
    }

    migrate(db, null, "expand", "contract");
    try (Connection c = connect(db)) {
      newWriter(c, UUID.randomUUID());
      // Old version retired before contract: it would fail here, which is why contract waits for
      // it.
      assertThatThrownBy(() -> oldWriter(c, "req-after-1003"))
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42703"));
      record("after V1003 (contract)", "new writer", "ok");
      record(
          "after V1003 (contract)",
          "old writer (old version retired before contract)",
          sqlFailure(() -> oldWriter(c, "req-after-1003")));
      assertThat(count(c)).isEqualTo(6);
    }
  }

  // --- the two application versions ---

  /** The version live today: the key is text, bound as a string. */
  private static void oldWriter(Connection c, String key) throws SQLException {
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO loan_trade (idempotency_key, position_id, counterparty_id, isin,"
                + " direction, quantity, unit_price_amount, unit_price_currency, trade_date,"
                + " settlement_date) VALUES (?, 'P1', 'CP-001', 'HK0000000001', 'BORROW', 10,"
                + " 41.26, 'HKD', DATE '2026-09-01', DATE '2026-09-03')")) {
      insert.setString(1, key);
      insert.executeUpdate();
    }
  }

  /**
   * The next version during expand: it writes the new column and, while the text column is still
   * {@code NOT NULL}, the text form beside it. After contract the text column is gone and it writes
   * the new column only; it never names a column that is not there in either schema, because it
   * checks.
   */
  private static void newWriter(Connection c, UUID key) throws SQLException {
    boolean textColumnPresent = hasColumn(c, "idempotency_key");
    String sql =
        textColumnPresent
            ? "INSERT INTO loan_trade (idempotency_key, idempotency_uuid, position_id,"
                + " counterparty_id, isin, direction, quantity, unit_price_amount,"
                + " unit_price_currency, trade_date, settlement_date) VALUES (?, ?, 'P1', 'CP-001',"
                + " 'HK0000000001', 'BORROW', 10, 41.26, 'HKD', DATE '2026-09-01',"
                + " DATE '2026-09-03')"
            : "INSERT INTO loan_trade (idempotency_uuid, position_id, counterparty_id, isin,"
                + " direction, quantity, unit_price_amount, unit_price_currency, trade_date,"
                + " settlement_date) VALUES (?, 'P1', 'CP-001', 'HK0000000001', 'BORROW', 10,"
                + " 41.26, 'HKD', DATE '2026-09-01', DATE '2026-09-03')";
    try (PreparedStatement insert = c.prepareStatement(sql)) {
      int i = 1;
      if (textColumnPresent) {
        insert.setString(i++, key.toString());
      }
      insert.setObject(i, key);
      insert.executeUpdate();
    }
  }

  /** The version written for the one-step change: the same column name, now bound as a UUID. */
  private static void newWriterAfterBreaking(Connection c, UUID key) throws SQLException {
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO loan_trade (idempotency_key, position_id, counterparty_id, isin,"
                + " direction, quantity, unit_price_amount, unit_price_currency, trade_date,"
                + " settlement_date) VALUES (?, 'P1', 'CP-001', 'HK0000000001', 'BORROW', 10,"
                + " 41.26, 'HKD', DATE '2026-09-01', DATE '2026-09-03')")) {
      insert.setObject(1, key);
      insert.executeUpdate();
    }
  }

  // --- infrastructure ---

  private static String newDatabase() {
    String name = "bluegreen_" + DATABASES.incrementAndGet();
    SharedPostgres.datasourceProperties(name);
    return SharedPostgres.jdbcUrl(name);
  }

  /** Production migrations plus the named lab steps, up to {@code target} (null: all). */
  private static void migrate(String url, String target, String... labSteps) {
    String[] locations = new String[labSteps.length + 1];
    locations[0] = "classpath:db/migration";
    for (int i = 0; i < labSteps.length; i++) {
      locations[i + 1] = "classpath:db/lab/bluegreen/" + labSteps[i];
    }
    var configuration =
        Flyway.configure()
            .dataSource(url, SharedPostgres.username(), SharedPostgres.password())
            .locations(locations);
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(url, SharedPostgres.username(), SharedPostgres.password());
  }

  private static void referenceRows(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO counterparty VALUES ('CP-001', 'Synthetic Borrower CP-001', 1000000.00,"
              + " 'HKD')");
      s.execute(
          "INSERT INTO security VALUES ('HK0000000001', 'Synthetic Equity', 'HKD', 41.26, 'HKD')");
      s.execute(
          "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
              + " outstanding_quantity, status, collateral_amount, collateral_currency, rule_type,"
              + " flat_rate) VALUES ('P1', 'CP-001', 'HK0000000001', 1000, 1000, 'OPEN', 50000.00,"
              + " 'HKD', 'FLAT', 0.0365)");
    }
  }

  private static boolean hasColumn(Connection c, String column) throws SQLException {
    try (var rows = c.getMetaData().getColumns(null, "public", "loan_trade", column)) {
      return rows.next();
    }
  }

  private static long nullUuids(Connection c) throws SQLException {
    return single(c, "SELECT count(*) FROM loan_trade WHERE idempotency_uuid IS NULL");
  }

  private static long count(Connection c) throws SQLException {
    return single(c, "SELECT count(*) FROM loan_trade");
  }

  private static long single(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
        var rows = s.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static String sqlState(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql) {
        return sql.getSQLState();
      }
    }
    return null;
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws Exception;
  }

  /** The failure an action raises, as "SQLState: first line of the message". */
  private static String sqlFailure(SqlAction action) {
    try {
      action.run();
      return "no failure";
    } catch (Exception e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof SQLException sql) {
          return "SQLState "
              + sql.getSQLState()
              + ": "
              + sql.getMessage().lines().findFirst().orElse("");
        }
      }
      return e.getClass().getName() + ": " + e.getMessage();
    }
  }

  private static synchronized void record(String schema, String action, String outcome) {
    EVIDENCE.append("%-40s | %-50s | %s%n".formatted(schema, action, outcome));
  }
}
