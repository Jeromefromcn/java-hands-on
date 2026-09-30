package com.jerome.slledger.adapter.persistence.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LabDb;
import com.jerome.slledger.adapter.persistence.testing.Timing;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercise 10c: consecutive holding days per position, by self-join over accrual_entry; then the
 * same with a window function, and in Java with one linear pass, timed at 1k, 10k and 100k entries.
 */
class HoldingStreakIT {

  static final String SELF_JOIN = LabDb.sqlFile("sql/holding-streaks.sql");
  static final String WINDOW = LabDb.sqlFile("sql/holding-streaks-window.sql");
  private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 1);
  private static final Map<Integer, String> CROSSOVER = new TreeMap<>();
  private static final StringBuilder PLANS = new StringBuilder();

  @AfterAll
  static void writeEvidence() {
    if (CROSSOVER.isEmpty()) {
      return; // the measurement tag was excluded: nothing was measured
    }
    StringBuilder out = new StringBuilder();
    out.append(
        "Median of %d runs after %d warm-ups, wall ms, including fetching the result.%n"
            .formatted(Timing.RUNS, Timing.WARMUPS));
    out.append(
        "%10s %12s %10s %10s %10s%n"
            .formatted("entries", "selfJoinMs", "windowMs", "javaMs", "streaks"));
    CROSSOVER.values().forEach(line -> out.append(line).append('\n'));
    out.append('\n').append(PLANS);
    Evidence.write("06-sql-streaks.txt", out.toString());
  }

  @Test
  void streaksAreTheRunsOfConsecutiveDays() throws SQLException {
    try (Connection c = migrated("streaks_shape")) {
      positions(c, 2);
      // S0: days 1-5, 8-9, 12. S1: days 1-10, no gap.
      for (int day : new int[] {1, 2, 3, 4, 5, 8, 9, 12}) {
        entry(c, "S0", DAY_1.plusDays(day - 1));
      }
      for (int day = 1; day <= 10; day++) {
        entry(c, "S1", DAY_1.plusDays(day - 1));
      }

      List<Streak> streaks = run(c, SELF_JOIN);

      assertThat(streaks)
          .containsExactly(
              new Streak("S0", DAY_1, DAY_1.plusDays(4), 5),
              new Streak("S0", DAY_1.plusDays(7), DAY_1.plusDays(8), 2),
              new Streak("S0", DAY_1.plusDays(11), DAY_1.plusDays(11), 1),
              new Streak("S1", DAY_1, DAY_1.plusDays(9), 10));
      assertThat(run(c, WINDOW)).isEqualTo(streaks);
      assertThat(java(c)).isEqualTo(streaks);
    }
  }

  @Tag("measurement")
  @ParameterizedTest(name = "{0} entries")
  @ValueSource(ints = {1_000, 10_000, 100_000})
  void crossover(int entries) throws Exception {
    try (Connection c = migrated("streaks_" + entries)) {
      positions(c, 10);
      // Ten positions; each held six days, then one day not, repeated: a streak every seven days.
      LabDb.execute(
          c,
          ("INSERT INTO accrual_entry (position_id, business_date, fee_amount, fee_currency)"
                  + " SELECT 'S' || (n %% 10), DATE '2020-01-01' + (n / 10) + (n / 10) / 6,"
                  + " 0.41, 'HKD' FROM generate_series(0, %d - 1) n")
              .formatted(entries));
      LabDb.execute(c, "ANALYZE");

      List<Streak> viaSelfJoin = run(c, SELF_JOIN);
      assertThat(run(c, WINDOW)).isEqualTo(viaSelfJoin);
      assertThat(java(c)).isEqualTo(viaSelfJoin);

      double selfJoinMs = Timing.medianMillis(() -> run(c, SELF_JOIN));
      double windowMs = Timing.medianMillis(() -> run(c, WINDOW));
      double javaMs = Timing.medianMillis(() -> java(c));
      CROSSOVER.put(
          entries,
          "%10d %12.1f %10.1f %10.1f %10d"
              .formatted(entries, selfJoinMs, windowMs, javaMs, viaSelfJoin.size()));
      PLANS.append("EXPLAIN self-join at %d entries:%n".formatted(entries));
      LabDb.explain(c, SELF_JOIN).forEach(line -> PLANS.append("  ").append(line).append('\n'));
      PLANS.append('\n');
      PLANS.append("EXPLAIN window form at %d entries:%n".formatted(entries));
      LabDb.explain(c, WINDOW).forEach(line -> PLANS.append("  ").append(line).append('\n'));
      PLANS.append('\n');
    }
  }

  // --- the implementations ---

  record Streak(String position, LocalDate start, LocalDate end, long days) {}

  static List<Streak> run(Connection c, String sql) throws SQLException {
    List<Streak> out = new ArrayList<>();
    try (var s = c.createStatement();
        var rows = s.executeQuery(sql)) {
      while (rows.next()) {
        out.add(
            new Streak(
                rows.getString("position_id"),
                rows.getObject("start_date", LocalDate.class),
                rows.getObject("end_date", LocalDate.class),
                rows.getLong("days")));
      }
    }
    return out;
  }

  /** Every entry's date, ordered, over the wire; one pass closes a streak at each gap. */
  static List<Streak> java(Connection c) throws SQLException {
    List<Streak> out = new ArrayList<>();
    try (var s = c.createStatement();
        var rows =
            s.executeQuery(
                "SELECT position_id, business_date FROM accrual_entry"
                    + " ORDER BY position_id, business_date")) {
      String position = null;
      LocalDate start = null;
      LocalDate previous = null;
      while (rows.next()) {
        String p = rows.getString(1);
        LocalDate d = rows.getObject(2, LocalDate.class);
        if (!p.equals(position) || !d.equals(previous.plusDays(1))) {
          if (position != null) {
            out.add(streak(position, start, previous));
          }
          position = p;
          start = d;
        }
        previous = d;
      }
      if (position != null) {
        out.add(streak(position, start, previous));
      }
    }
    return out;
  }

  private static Streak streak(String position, LocalDate start, LocalDate end) {
    return new Streak(position, start, end, end.toEpochDay() - start.toEpochDay() + 1);
  }

  // --- fixtures ---

  private static Connection migrated(String database) throws SQLException {
    String url = LabDb.create(database);
    LabDb.migrate(url, null);
    return LabDb.connect(url);
  }

  private static void positions(Connection c, int count) throws SQLException {
    LabDb.execute(
        c,
        "INSERT INTO counterparty VALUES ('CP-001', 'Synthetic Borrower CP-001', 100000000.00,"
            + " 'HKD')");
    LabDb.execute(
        c, "INSERT INTO security VALUES ('HK0000000001', 'Synthetic Equity', 'HKD', 41.26, 'HKD')");
    LabDb.execute(
        c,
        ("INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate)"
                + " SELECT 'S' || n, 'CP-001', 'HK0000000001', 1000, 1000, 'OPEN', 1000.00, 'HKD',"
                + " 'FLAT', 0.0365 FROM generate_series(0, %d - 1) n")
            .formatted(count));
  }

  private static void entry(Connection c, String position, LocalDate day) throws SQLException {
    LabDb.execute(
        c,
        "INSERT INTO accrual_entry VALUES ('%s', DATE '%s', 0.41, 'HKD')".formatted(position, day));
  }
}
