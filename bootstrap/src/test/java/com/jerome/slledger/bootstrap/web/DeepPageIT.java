package com.jerome.slledger.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offset against keyset on 500,000 positions, shallow and deep, over HTTP. Measurement only: it
 * asserts that both return the same rows and records the timings; see docs/decisions/07-web.md.
 */
@Tag("measurement")
class DeepPageIT {

  private static final int POSITIONS = 500_000;
  private static final int SIZE = 20;
  private static final int WARM_UP = 5;
  private static final int RUNS = 20;

  private static WebLedger ledger;
  private static final StringBuilder EVIDENCE = new StringBuilder();

  @BeforeAll
  static void start() {
    ledger = WebLedger.start();
    long started = System.nanoTime();
    ledger
        .jdbc
        .sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) SELECT 'LP-' || lpad(n::text, 6, '0'), 'CP-001', ?,"
                + " 1000, 1000, 'OPEN', 1000.00, 'HKD', 'FLAT', 0.0365"
                + " FROM generate_series(1, ?) n")
        .params(WebLedger.HK_EQUITY, POSITIONS)
        .update();
    ledger.jdbc.sql("ANALYZE loan_position").update();
    EVIDENCE.append(
        """
        Stage 07 · section 12 — offset vs keyset on %,d positions (DeepPageIT, -Pmeasure)
        Page size %d; per point %d warm-up requests, then the median of %d, over HTTP to the
        running application (Tomcat, Jackson, JdbcClient, Postgres in Testcontainers).
        Fixture inserted in %d ms.

        """
            .formatted(POSITIONS, SIZE, WARM_UP, RUNS, (System.nanoTime() - started) / 1_000_000));
  }

  @AfterAll
  static void stop() {
    Evidence.write("07-pagination.txt", EVIDENCE.toString());
    ledger.close();
  }

  @Test
  @DisplayName("the same rows both ways; offset slows with depth, keyset does not")
  void offsetAgainstKeyset() {
    EVIDENCE.append(
        "%-10s %-14s %-12s %-14s%n".formatted("offset", "offset (ms)", "keyset (ms)", "first id"));
    for (int offset : new int[] {0, 250_000, 499_980}) {
      String offsetUri = "/loans?page=%d&size=%d".formatted(offset / SIZE, SIZE);
      String after = offset == 0 ? "" : "LP-%06d".formatted(offset);
      String keysetUri = "/loans?after=%s&size=%d".formatted(after, SIZE);

      List<String> byOffset = JsonPath.read(get(offsetUri), "$.rows[*].id");
      List<String> byKeyset = JsonPath.read(get(keysetUri), "$.rows[*].id");
      assertThat(byKeyset).isEqualTo(byOffset).hasSize(SIZE);

      double offsetMs = median(offsetUri);
      double keysetMs = median(keysetUri);
      EVIDENCE.append(
          "%-10d %-14.2f %-12.2f %-14s%n"
              .formatted(offset, offsetMs, keysetMs, byOffset.getFirst()));
    }

    EVIDENCE.append("\nWhere the offset request's time goes, at offset 499,980:\n\n");
    explain("Offset page query", "SELECT * FROM loan_position ORDER BY id LIMIT 20 OFFSET 499980");
    explain("Offset request's total, run on every page", "SELECT count(*) FROM loan_position");
    explain(
        "Keyset query at the same row",
        "SELECT * FROM loan_position WHERE id > 'LP-499980' ORDER BY id LIMIT 21");
  }

  private static void explain(String title, String sql) {
    List<String> plan =
        ledger
            .jdbc
            .sql("EXPLAIN (ANALYZE, BUFFERS) " + sql)
            .query((rs, n) -> rs.getString(1))
            .list();
    EVIDENCE.append(title).append(":\n  ").append(sql).append('\n');
    plan.forEach(line -> EVIDENCE.append("    ").append(line).append('\n'));
    EVIDENCE.append('\n');
  }

  private static double median(String uri) {
    for (int i = 0; i < WARM_UP; i++) {
      get(uri);
    }
    List<Double> millis = new ArrayList<>();
    for (int i = 0; i < RUNS; i++) {
      long started = System.nanoTime();
      get(uri);
      millis.add((System.nanoTime() - started) / 1_000_000.0);
    }
    Collections.sort(millis);
    return (millis.get(RUNS / 2 - 1) + millis.get(RUNS / 2)) / 2;
  }

  private static String get(String uri) {
    return ledger
        .http
        .get()
        .uri(uri)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }
}
