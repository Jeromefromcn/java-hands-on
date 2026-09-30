package com.jerome.slledger.adapter.persistence.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LabDb;
import com.jerome.slledger.adapter.persistence.testing.Timing;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercise 10b: collateral rolled up a pool hierarchy with {@code WITH RECURSIVE}, per currency;
 * then the same roll-up in Java over every pool, timed against the query at 1k, 10k and 100k pools.
 */
class RecursiveCteIT {

  static final String QUERY = LabDb.sqlFile("sql/pool-rollup.sql");
  static final String ONE = LabDb.sqlFile("sql/pool-rollup-one.sql");

  /** Pool 1's subtree: 1, 10–19, 100–199, … about a ninth of each generated tree. */
  static final String ONE_ROOT = "POOL-000001";

  private static final Map<Integer, String> CROSSOVER = new TreeMap<>();
  private static final StringBuilder PLANS = new StringBuilder();

  @AfterAll
  static void writeEvidence() {
    StringBuilder out = new StringBuilder();
    out.append(
        "Median of %d runs after %d warm-ups, wall ms, including fetching the result.%n"
            .formatted(Timing.RUNS, Timing.WARMUPS));
    out.append(
        ("All pools: every pool's roll-up (sqlMs vs javaMs). One pool: %s's roll-up only (sqlOneMs"
                + " vs javaOneMs; the Java side still loads every pool, since it cannot know the"
                + " subtree without them).%n")
            .formatted(ONE_ROOT));
    out.append(
        "%10s %10s %10s %10s %10s %10s%n"
            .formatted("pools", "sqlMs", "javaMs", "resultRows", "sqlOneMs", "javaOneMs"));
    CROSSOVER.values().forEach(line -> out.append(line).append('\n'));
    out.append('\n').append(PLANS);
    Evidence.write("06-sql-recursive.txt", out.toString());
  }

  /**
   * ROOT → A, B; A → A1, A2. HKD everywhere, and one USD holding in A2, which reaches A and ROOT as
   * a line of its own.
   */
  @Test
  void eachPoolRollsUpItsSubtreePerCurrency() throws SQLException {
    try (Connection c = migrated("pools_tree")) {
      LabDb.execute(
          c,
          "INSERT INTO collateral_pool VALUES ('ROOT', NULL, 'Root'), ('A', 'ROOT', 'A'),"
              + " ('B', 'ROOT', 'B'), ('A1', 'A', 'A1'), ('A2', 'A', 'A2')");
      LabDb.execute(
          c,
          "INSERT INTO pool_holding VALUES ('ROOT', 100.00, 'HKD'), ('A', 10.00, 'HKD'),"
              + " ('B', 20.00, 'HKD'), ('A1', 1.00, 'HKD'), ('A2', 2.00, 'HKD'),"
              + " ('A2', 5.00, 'USD')");

      List<Line> lines = sql(c);

      assertThat(lines)
          .containsExactly(
              line("A", "HKD", "13"),
              line("A", "USD", "5"),
              line("A1", "HKD", "1"),
              line("A2", "HKD", "2"),
              line("A2", "USD", "5"),
              line("B", "HKD", "20"),
              line("ROOT", "HKD", "133"),
              line("ROOT", "USD", "5"));
      assertThat(java(c)).isEqualTo(lines);
    }
  }

  /** X's parent is Y and Y's is X. Without the CYCLE clause this recursion never ends. */
  @Test
  @Timeout(10)
  void aCycleTerminatesAndCountsEachPoolOnce() throws SQLException {
    try (Connection c = migrated("pools_cycle")) {
      LabDb.execute(c, "INSERT INTO collateral_pool VALUES ('X', NULL, 'X'), ('Y', 'X', 'Y')");
      LabDb.execute(c, "UPDATE collateral_pool SET parent_id = 'Y' WHERE id = 'X'");
      LabDb.execute(c, "INSERT INTO pool_holding VALUES ('X', 1.00, 'HKD'), ('Y', 2.00, 'HKD')");

      assertThat(sql(c)).containsExactly(line("X", "HKD", "3"), line("Y", "HKD", "3"));
    }
  }

  @ParameterizedTest(name = "{0} pools")
  @ValueSource(ints = {1_000, 10_000, 100_000})
  void crossover(int pools) throws Exception {
    try (Connection c = migrated("pools_" + pools)) {
      // Fan-out 10: pool n's parent is n / 10, pool 0 is the root. One holding per pool, every
      // seventh one in USD.
      LabDb.execute(
          c,
          ("INSERT INTO collateral_pool"
                  + " SELECT 'POOL-' || lpad(n::text, 6, '0'),"
                  + " CASE WHEN n = 0 THEN NULL ELSE 'POOL-' || lpad((n / 10)::text, 6, '0') END,"
                  + " 'Pool ' || n FROM generate_series(0, %d - 1) n")
              .formatted(pools));
      LabDb.execute(
          c,
          ("INSERT INTO pool_holding SELECT 'POOL-' || lpad(n::text, 6, '0'), (n %% 100) + 0.01,"
                  + " CASE WHEN n %% 7 = 0 THEN 'USD' ELSE 'HKD' END"
                  + " FROM generate_series(0, %d - 1) n")
              .formatted(pools));
      LabDb.execute(c, "ANALYZE");

      List<Line> viaSql = sql(c);
      assertThat(java(c)).isEqualTo(viaSql);

      List<Line> oneViaSql = sqlOne(c);
      List<Line> oneViaJava = javaOne(c);
      assertThat(oneViaJava).isEqualTo(oneViaSql);
      assertThat(oneViaSql)
          .isEqualTo(viaSql.stream().filter(l -> l.pool().equals(ONE_ROOT)).toList());

      double sqlMs = Timing.medianMillis(() -> sql(c));
      double javaMs = Timing.medianMillis(() -> java(c));
      double sqlOneMs = Timing.medianMillis(() -> sqlOne(c));
      double javaOneMs = Timing.medianMillis(() -> javaOne(c));
      CROSSOVER.put(
          pools,
          "%10d %10.1f %10.1f %10d %10.1f %10.1f"
              .formatted(pools, sqlMs, javaMs, viaSql.size(), sqlOneMs, javaOneMs));
      PLANS.append("EXPLAIN at %d pools, all pools:%n".formatted(pools));
      LabDb.explain(c, QUERY).forEach(line -> PLANS.append("  ").append(line).append('\n'));
      PLANS.append('\n');
      PLANS.append("EXPLAIN at %d pools, one pool (%s):%n".formatted(pools, ONE_ROOT));
      LabDb.explain(c, ONE.replace("?", "'" + ONE_ROOT + "'"))
          .forEach(line -> PLANS.append("  ").append(line).append('\n'));
      PLANS.append('\n');
    }
  }

  // --- the two implementations ---

  static List<Line> sqlOne(Connection c) throws SQLException {
    List<Line> out = new ArrayList<>();
    try (var s = c.prepareStatement(ONE)) {
      s.setString(1, ONE_ROOT);
      try (var rows = s.executeQuery()) {
        while (rows.next()) {
          out.add(new Line(rows.getString(1), rows.getString(2).trim(), rows.getBigDecimal(3)));
        }
      }
    }
    return out;
  }

  static List<Line> javaOne(Connection c) throws SQLException {
    return java(c).stream().filter(l -> l.pool().equals(ONE_ROOT)).toList();
  }

  /** One roll-up line; the amount compared by value, so 13 and 13.000000 are the same line. */
  record Line(String pool, String currency, BigDecimal total) {

    Line {
      total = total.stripTrailingZeros();
    }
  }

  static List<Line> sql(Connection c) throws SQLException {
    List<Line> out = new ArrayList<>();
    try (var s = c.createStatement();
        var rows = s.executeQuery(QUERY)) {
      while (rows.next()) {
        out.add(new Line(rows.getString(1), rows.getString(2).trim(), rows.getBigDecimal(3)));
      }
    }
    return out;
  }

  /**
   * Every pool and holding over the wire, then one post-order pass: each pool's totals are its own
   * holdings plus its children's totals. Each pool is visited once, not once per ancestor.
   */
  static List<Line> java(Connection c) throws SQLException {
    Map<String, List<String>> children = new HashMap<>();
    List<String> roots = new ArrayList<>();
    try (var s = c.createStatement();
        var rows = s.executeQuery("SELECT id, parent_id FROM collateral_pool")) {
      while (rows.next()) {
        String id = rows.getString(1);
        String parent = rows.getString(2);
        children.computeIfAbsent(id, k -> new ArrayList<>());
        if (parent == null) {
          roots.add(id);
        } else {
          children.computeIfAbsent(parent, k -> new ArrayList<>()).add(id);
        }
      }
    }
    Map<String, Map<String, BigDecimal>> totals = new HashMap<>();
    try (var s = c.createStatement();
        var rows = s.executeQuery("SELECT pool_id, currency, amount FROM pool_holding")) {
      while (rows.next()) {
        totals
            .computeIfAbsent(rows.getString(1), k -> new TreeMap<>())
            .merge(rows.getString(2).trim(), rows.getBigDecimal(3), BigDecimal::add);
      }
    }
    // Iterative post-order: a deep hierarchy must not overflow the stack.
    Deque<String> stack = new ArrayDeque<>(roots);
    List<String> order = new ArrayList<>();
    while (!stack.isEmpty()) {
      String pool = stack.pop();
      order.add(pool);
      children.get(pool).forEach(stack::push);
    }
    for (int i = order.size() - 1; i >= 0; i--) {
      String pool = order.get(i);
      Map<String, BigDecimal> own = totals.computeIfAbsent(pool, k -> new TreeMap<>());
      for (String child : children.get(pool)) {
        totals
            .getOrDefault(child, Map.of())
            .forEach((currency, amount) -> own.merge(currency, amount, BigDecimal::add));
      }
    }
    List<Line> out = new ArrayList<>();
    new TreeMap<>(totals)
        .forEach(
            (pool, byCurrency) ->
                byCurrency.forEach((currency, total) -> out.add(new Line(pool, currency, total))));
    return out;
  }

  private static Line line(String pool, String currency, String total) {
    return new Line(pool, currency, new BigDecimal(total));
  }

  private static Connection migrated(String database) throws SQLException {
    String url = LabDb.create(database);
    LabDb.migrate(url, null, "classpath:db/lab/pools");
    return LabDb.connect(url);
  }
}
