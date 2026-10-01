package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.bootstrap.web.WebLedger;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Whose rows a caller sees. Counterparty CP-001 has LP-A1 and LP-A2, CP-002 has LP-B1. The first
 * test is the failure, against a test-only endpoint; the rest are the real endpoints.
 */
class CounterpartyScopeIT {

  private static WebLedger ledger;
  private static final StringBuilder EVIDENCE =
      new StringBuilder("Stage 13 · section 4 — whose rows (CounterpartyScopeIT)\n\n");

  @BeforeAll
  static void start() {
    ledger =
        WebLedger.start(
            List.of(VulnerableLoanController.class),
            "--ledger.marketdata.base-url=http://127.0.0.1:9");
    for (String[] position :
        new String[][] {
          {"LP-A1", "CP-001"}, {"LP-A2", "CP-001"}, {"LP-B1", "CP-002"}, {"LP-A3", "CP-001"}
        }) {
      ledger
          .jdbc
          .sql(
              "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                  + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                  + " rule_type, flat_rate) VALUES (?, ?, ?, 1000, 1000, 'OPEN', 1234.50, 'HKD',"
                  + " 'FLAT', 0.0365)")
          .params(position[0], position[1], WebLedger.HK_EQUITY)
          .update();
    }
    ledger
        .jdbc
        .sql(
            "INSERT INTO accrual_entry (position_id, business_date, fee_amount, fee_currency)"
                + " VALUES ('LP-B1', date '2026-03-02', 0.34, 'HKD')")
        .update();
  }

  @AfterAll
  static void stop() {
    Evidence.write("13-counterparty-scope.txt", EVIDENCE.toString());
    ledger.close();
  }

  private static EntityExchangeResult<String> get(String token, String path) {
    return LedgerApp.http(ledger.app, token).get().uri(path).exchange().returnResult(String.class);
  }

  private static String body(String token, String path) {
    return get(token, path).getResponseBody();
  }

  @Test
  @DisplayName("reproducesPositionOfAnotherCounterpartyReadByIdChange")
  void reproducesPositionOfAnotherCounterpartyReadByIdChange() {
    // See docs/decisions/13-security.md
    // CP-002 holds LP-B1. Its token is valid, and it holds the counterparty role, which is all the
    // test-only endpoint asks. It changes the id in the URL to one it was never given.
    EntityExchangeResult<String> result =
        get(TestTokens.counterparty("CP-002"), "/vulnerable/loans/LP-A1");

    assertThat(result.getStatus().value()).isEqualTo(200);
    assertThat(result.getResponseBody())
        .contains("\"counterparty\"")
        .contains("CP-001")
        .contains("1234.50");
    EVIDENCE
        .append(
            "The over-read, against the test-only endpoint that checks the role and not the owner:\n")
        .append("  CP-002's token, GET /vulnerable/loans/LP-A1 -> 200 ")
        .append(result.getResponseBody())
        .append("\n\n");
  }

  @Test
  @DisplayName("a foreign position is a 404, the same as a position that does not exist")
  void foreignPositionIsNotFound() {
    EntityExchangeResult<String> foreign = get(TestTokens.counterparty("CP-002"), "/loans/LP-A1");
    EntityExchangeResult<String> missing = get(TestTokens.counterparty("CP-002"), "/loans/LP-ZZZ");

    assertThat(foreign.getStatus().value()).isEqualTo(404);
    assertThat(missing.getStatus().value()).isEqualTo(404);
    // Same body but for the id the caller itself named and the per-request trace id.
    assertThat(normalise(foreign.getResponseBody(), "LP-A1"))
        .isEqualTo(normalise(missing.getResponseBody(), "LP-ZZZ"));
    assertThat(get(TestTokens.counterparty("CP-001"), "/loans/LP-A1").getStatus().value())
        .isEqualTo(200);
    EVIDENCE
        .append("The fix, the real endpoint:\n")
        .append("  CP-002's token, GET /loans/LP-A1  -> 404 ")
        .append(foreign.getResponseBody())
        .append("\n  CP-002's token, GET /loans/LP-ZZZ -> 404 ")
        .append(missing.getResponseBody())
        .append("\n\n");
  }

  private static String normalise(String problem, String id) {
    return problem.replace(id, "<id>").replaceAll("\"traceId\":\"[0-9a-f]+\"", "\"traceId\":\"-\"");
  }

  @Test
  @DisplayName("accruals of a foreign position are a 404")
  void foreignAccrualsAreNotFound() {
    String range = "/accruals?from=2026-03-02&to=2026-03-03";

    assertThat(get(TestTokens.counterparty("CP-001"), "/loans/LP-B1" + range).getStatus().value())
        .isEqualTo(404);
    assertThat(get(TestTokens.counterparty("CP-002"), "/loans/LP-B1" + range).getStatus().value())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("the list, its total and the keyset walk hold only the caller's rows")
  void listIsFilteredInTheQuery() {
    String a = TestTokens.counterparty("CP-001");

    String page = body(a, "/loans?page=0&size=2");
    assertThat(page).contains("\"totalRows\":3").contains("LP-A1").contains("LP-A2");
    assertThat(page).doesNotContain("LP-B1").doesNotContain("CP-002");

    String firstSlice = body(a, "/loans?after=&size=2");
    assertThat(firstSlice).contains("LP-A1").contains("LP-A2").doesNotContain("LP-B1");
    assertThat(body(a, "/loans?after=LP-A2&size=5")).contains("LP-A3").doesNotContain("LP-B1");

    String b = body(TestTokens.counterparty("CP-002"), "/loans");
    assertThat(b).contains("\"totalRows\":1").contains("LP-B1").doesNotContain("LP-A");
  }

  @Test
  @DisplayName("naming another counterparty in the filter is a 403; naming one's own is fine")
  void filterNamingSomeoneElse() {
    String a = TestTokens.counterparty("CP-001");

    assertThat(get(a, "/loans?counterparty=CP-002").getStatus().value()).isEqualTo(403);
    assertThat(get(a, "/loans?counterparty=CP-001").getStatus().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("staff see every counterparty, and may filter to one")
  void staffSeeAll() {
    assertThat(body(TestTokens.ops(), "/loans")).contains("\"totalRows\":4");
    String onlyB = body(TestTokens.trader(), "/loans?counterparty=CP-002");
    assertThat(onlyB).contains("\"totalRows\":1").contains("LP-B1");
  }

  @Test
  @DisplayName("a counterparty role with no counterparty claim: the list is a 403, an item a 404")
  void counterpartyWithoutClaim() {
    String token = TestTokens.builder().roles("ledger-counterparty").build();

    assertThat(get(token, "/loans").getStatus().value()).isEqualTo(403);
    assertThat(get(token, "/loans/LP-A1").getStatus().value()).isEqualTo(404);
  }
}
