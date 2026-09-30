package com.jerome.slledger.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What each way of paging returns when a row is inserted between two page requests. See
 * docs/decisions/07-web.md.
 */
class PaginationConsistencyIT {

  private WebLedger ledger;

  @BeforeEach
  void start() {
    ledger = WebLedger.start();
    // LP-0010, LP-0020, … LP-0300: gaps leave room to insert ahead of any page boundary.
    ledger
        .jdbc
        .sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) SELECT 'LP-' || lpad((n * 10)::text, 4, '0'), 'CP-001',"
                + " ?, 1000, 1000, 'OPEN', 1000.00, 'HKD', 'FLAT', 0.0365"
                + " FROM generate_series(1, 30) n")
        .param(WebLedger.HK_EQUITY)
        .update();
  }

  @AfterEach
  void stop() {
    ledger.close();
  }

  @Test
  @DisplayName(
      "offset: a row inserted ahead of the boundary repeats the last row of the page before")
  void reproducesOffsetPageDriftUnderInsert() {
    // See docs/decisions/07-web.md
    List<String> page0 = ids(get("/loans?page=0&size=10"));
    insertAheadOfEverything();
    List<String> page1 = ids(get("/loans?page=1&size=10"));

    assertThat(page0.getLast()).isEqualTo("LP-0100");
    assertThat(page1.getFirst()).isEqualTo("LP-0100");
  }

  @Test
  @DisplayName(
      "keyset: walking every slice returns each row that existed at the start exactly once")
  void keysetIsStableUnderInsert() {
    List<String> seen = new ArrayList<>();
    String body = get("/loans?after=&size=10");
    seen.addAll(ids(body));
    insertAheadOfEverything();
    String next = JsonPath.read(body, "$.next");
    while (next != null) {
      body = get("/loans?after=" + next + "&size=10");
      seen.addAll(ids(body));
      next = hasNext(body) ? JsonPath.read(body, "$.next") : null;
    }

    assertThat(seen).hasSize(30).doesNotHaveDuplicates().isSorted().doesNotContain("LP-0005");
  }

  @Test
  @DisplayName("the last slice has no next cursor, and a slice carries no total")
  void lastSliceHasNoNext() {
    String last = get("/loans?after=LP-0290&size=10");

    assertThat(ids(last)).containsExactly("LP-0300");
    assertThat(hasNext(last)).isFalse();
    assertThat(last).doesNotContain("totalRows").doesNotContain("\"page\"");
  }

  @Test
  @DisplayName("page and after together are a 400")
  void bothIsBadRequest() {
    ledger
        .http
        .get()
        .uri("/loans?page=1&after=LP-0100")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("urn:sl-ledger:problem:invalid-query");
  }

  private void insertAheadOfEverything() {
    ledger
        .jdbc
        .sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) VALUES ('LP-0005', 'CP-001', ?, 1000, 1000, 'OPEN',"
                + " 1000.00, 'HKD', 'FLAT', 0.0365)")
        .param(WebLedger.HK_EQUITY)
        .update();
  }

  private String get(String uri) {
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

  private static List<String> ids(String body) {
    return JsonPath.read(body, "$.rows[*].id");
  }

  private static boolean hasNext(String body) {
    List<String> next = JsonPath.read(body, "$..next");
    return !next.isEmpty();
  }
}
