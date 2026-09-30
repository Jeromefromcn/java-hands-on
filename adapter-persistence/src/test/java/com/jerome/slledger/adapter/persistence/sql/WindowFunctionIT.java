package com.jerome.slledger.adapter.persistence.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LabDb;
import com.jerome.slledger.adapter.persistence.testing.Timing;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercise 10a: top positions by market value per counterparty, with ROW_NUMBER, RANK and
 * DENSE_RANK side by side; then the same ranking done in Java over every position, timed against
 * the query at 1k, 10k and 100k positions.
 */
class WindowFunctionIT {

  static final String QUERY = LabDb.sqlFile("sql/top-positions-by-value.sql");
  private static final Map<Integer, String> CROSSOVER = new java.util.TreeMap<>();
  private static final StringBuilder PLANS = new StringBuilder();
  private static volatile String defaultWorkMem = "?";

  @AfterAll
  static void writeEvidence() {
    StringBuilder out = new StringBuilder();
    out.append(
        "Median of %d runs after %d warm-ups, wall ms, including fetching the result.%n"
            .formatted(Timing.RUNS, Timing.WARMUPS));
    out.append(
        "sqlMs at the server default work_mem (%s); sqlMs64 in a session with work_mem = '64MB'.%n"
            .formatted(defaultWorkMem));
    out.append(
        "%10s %10s %10s %10s %10s%n"
            .formatted("positions", "sqlMs", "sqlMs64", "javaMs", "resultRows"));
    CROSSOVER.values().forEach(line -> out.append(line).append('\n'));
    out.append('\n').append(PLANS);
    Evidence.write("06-sql-window.txt", out.toString());
  }

  /**
   * Values 500, 500, 400, 400, 300, 200. Each function's top three is a different set: ROW_NUMBER
   * cuts through the 400s by id, RANK keeps both 400s and skips to 5, DENSE_RANK keeps the 300.
   */
  @Test
  void theThreeFunctionsDisagreeOnTies() throws SQLException {
    String url = LabDb.create("window_ties");
    LabDb.migrate(url, null);
    try (Connection c = LabDb.connect(url)) {
      reference(c);
      LabDb.execute(
          c,
          "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
              + " outstanding_quantity, status, collateral_amount, collateral_currency, rule_type,"
              + " flat_rate) VALUES"
              + " ('P1', 'CP-001', 'HK0000000001', 500, 500, 'OPEN', 1.00, 'HKD', 'FLAT', 0.01),"
              + " ('P2', 'CP-001', 'HK0000000001', 500, 500, 'OPEN', 1.00, 'HKD', 'FLAT', 0.01),"
              + " ('P3', 'CP-001', 'HK0000000001', 400, 400, 'OPEN', 1.00, 'HKD', 'FLAT', 0.01),"
              + " ('P4', 'CP-001', 'HK0000000001', 400, 400, 'OPEN', 1.00, 'HKD', 'FLAT', 0.01),"
              + " ('P5', 'CP-001', 'HK0000000001', 300, 300, 'OPEN', 1.00, 'HKD', 'FLAT', 0.01),"
              + " ('P6', 'CP-001', 'HK0000000001', 200, 200, 'OPEN', 1.00, 'HKD', 'FLAT', 0.01),"
              // Same counterparty, USD: ranked on its own, never against the HKD values.
              + " ('P7', 'CP-001', 'US0000000001', 900, 900, 'OPEN', 1.00, 'USD', 'FLAT', 0.01)");

      List<Ranked> rows = sql(c);

      List<Ranked> hkd = rows.stream().filter(r -> r.currency.equals("HKD")).toList();
      assertThat(ids(hkd, r -> r.rn <= 3)).containsExactly("P1", "P2", "P3");
      assertThat(ids(hkd, r -> r.rnk <= 3)).containsExactly("P1", "P2", "P3", "P4");
      assertThat(ids(hkd, r -> r.drnk <= 3)).containsExactly("P1", "P2", "P3", "P4", "P5");
      assertThat(hkd).extracting(r -> r.rnk).containsExactly(1L, 1L, 3L, 3L, 5L);
      assertThat(hkd).extracting(r -> r.drnk).containsExactly(1L, 1L, 2L, 2L, 3L);
      assertThat(rows.stream().filter(r -> r.currency.equals("USD")))
          .singleElement()
          .satisfies(r -> assertThat(r.rn).isEqualTo(1));
      assertThat(java(c)).isEqualTo(rows);
    }
  }

  @ParameterizedTest(name = "{0} positions")
  @ValueSource(ints = {1_000, 10_000, 100_000})
  void crossover(int positions) throws Exception {
    String url = LabDb.create("window_" + positions);
    LabDb.migrate(url, null);
    try (Connection c = LabDb.connect(url)) {
      reference(c);
      // 10 positions per counterparty, every fifth one priced in USD, quantities with many ties.
      LabDb.execute(
          c,
          ("INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                  + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                  + " rule_type, flat_rate)"
                  + " SELECT 'LP-' || lpad(n::text, 6, '0'), 'CP-' || lpad((n / 10)::text, 5, '0'),"
                  + " CASE WHEN n %% 5 = 0 THEN 'US0000000001' ELSE 'HK0000000001' END,"
                  + " 1000, (n * 7919) %% 50 + 1, 'PARTIALLY_RETURNED', 1.00,"
                  + " CASE WHEN n %% 5 = 0 THEN 'USD' ELSE 'HKD' END, 'FLAT', 0.01"
                  + " FROM generate_series(1, %d) n")
              .formatted(positions));
      LabDb.execute(c, "ANALYZE");

      List<Ranked> viaSql = sql(c);
      assertThat(java(c)).isEqualTo(viaSql);

      defaultWorkMem = showWorkMem(c);
      double sqlMs = Timing.medianMillis(() -> sql(c));
      double javaMs = Timing.medianMillis(() -> java(c));
      PLANS.append("EXPLAIN at %d positions, work_mem %s:%n".formatted(positions, defaultWorkMem));
      LabDb.explain(c, QUERY).forEach(line -> PLANS.append("  ").append(line).append('\n'));
      PLANS.append('\n');

      // The same query with room to sort in memory: separates "SQL is slower" from "this sort
      // spilled to disk at the default work_mem".
      LabDb.execute(c, "SET work_mem = '64MB'");
      double sqlMs64 = Timing.medianMillis(() -> sql(c));
      if (positions == 100_000) {
        PLANS.append("EXPLAIN at %d positions, work_mem 64MB:%n".formatted(positions));
        LabDb.explain(c, QUERY).forEach(line -> PLANS.append("  ").append(line).append('\n'));
        PLANS.append('\n');
      }
      CROSSOVER.put(
          positions,
          "%10d %10.1f %10.1f %10.1f %10d"
              .formatted(positions, sqlMs, sqlMs64, javaMs, viaSql.size()));
    }
  }

  // --- the two implementations ---

  record Ranked(
      String counterparty,
      String currency,
      String id,
      BigDecimal marketValue,
      long rn,
      long rnk,
      long drnk) {}

  static List<Ranked> sql(Connection c) throws SQLException {
    List<Ranked> out = new ArrayList<>();
    try (var s = c.createStatement();
        var rows = s.executeQuery(QUERY)) {
      while (rows.next()) {
        out.add(
            new Ranked(
                rows.getString("counterparty_id"),
                rows.getString("currency").trim(),
                rows.getString("id"),
                rows.getBigDecimal("market_value"),
                rows.getLong("rn"),
                rows.getLong("rnk"),
                rows.getLong("drnk")));
      }
    }
    return out;
  }

  /** Every position over the wire, then grouped, sorted and ranked in memory. */
  static List<Ranked> java(Connection c) throws SQLException {
    record Row(String counterparty, String currency, String id, BigDecimal value) {}
    Map<String, List<Row>> groups = new LinkedHashMap<>();
    try (var s = c.createStatement();
        var rows =
            s.executeQuery(
                "SELECT p.counterparty_id, s.market_price_currency, p.id, p.outstanding_quantity,"
                    + " s.market_price_amount FROM loan_position p JOIN security s"
                    + " ON s.isin = p.isin")) {
      while (rows.next()) {
        String currency = rows.getString(2).trim();
        Row row =
            new Row(
                rows.getString(1),
                currency,
                rows.getString(3),
                rows.getBigDecimal(5).multiply(BigDecimal.valueOf(rows.getLong(4))));
        groups.computeIfAbsent(row.counterparty + "|" + currency, k -> new ArrayList<>()).add(row);
      }
    }
    List<Ranked> out = new ArrayList<>();
    groups.keySet().stream()
        .sorted()
        .forEach(
            key -> {
              List<Row> group = groups.get(key);
              group.sort(Comparator.comparing(Row::value).reversed().thenComparing(Row::id));
              long rank = 0;
              long dense = 0;
              BigDecimal previous = null;
              for (int i = 0; i < group.size(); i++) {
                Row row = group.get(i);
                if (previous == null || row.value.compareTo(previous) != 0) {
                  rank = i + 1;
                  dense++;
                  previous = row.value;
                }
                if (dense > 3) {
                  break;
                }
                out.add(
                    new Ranked(
                        row.counterparty, row.currency, row.id, row.value, i + 1, rank, dense));
              }
            });
    return out;
  }

  private static String showWorkMem(Connection c) throws SQLException {
    try (var s = c.createStatement();
        var rows = s.executeQuery("SHOW work_mem")) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static List<String> ids(List<Ranked> rows, java.util.function.Predicate<Ranked> keep) {
    return rows.stream().filter(keep).map(Ranked::id).toList();
  }

  private static void reference(Connection c) throws SQLException {
    LabDb.execute(
        c,
        "INSERT INTO security VALUES ('HK0000000001', 'Synthetic HK Equity', 'HKD', 1.00, 'HKD'),"
            + " ('US0000000001', 'Synthetic US Equity', 'USD', 1.00, 'USD')");
    LabDb.execute(
        c,
        "INSERT INTO counterparty SELECT 'CP-' || lpad(n::text, 5, '0'),"
            + " 'Synthetic Borrower ' || n, 100000000.00, 'HKD' FROM generate_series(0, 10000) n");
    LabDb.execute(
        c,
        "INSERT INTO counterparty VALUES ('CP-001', 'Synthetic Borrower CP-001', 100000000.00,"
            + " 'HKD')");
  }
}
