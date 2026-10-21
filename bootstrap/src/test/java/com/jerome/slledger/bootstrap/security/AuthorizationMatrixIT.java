package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.bootstrap.web.WebLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * Every endpoint called as every role. The expected sets are the authorization model: who may do
 * what, as the decision record states it. A call that is allowed is judged only by not being a 401
 * or 403 (what it answers beyond that is the endpoint's own tests' business); one that is not
 * allowed must be exactly 403, with no body that names a role.
 */
class AuthorizationMatrixIT {

  private static WebLedger ledger;

  private record Endpoint(
      String name, HttpMethod method, String path, Supplier<String> body, Set<String> allowed) {}

  private static final Set<String> READERS = Set.of("trader", "ops", "counterparty");
  private static final Set<String> BOOKERS = Set.of("trader");
  private static final Set<String> QUOTERS = Set.of("trader", "ops");

  private static final String ROLLOUT = "/rollouts/accrual.day-count-by-market";

  private static final List<Endpoint> ENDPOINTS =
      List.of(
          new Endpoint("GET /loans", HttpMethod.GET, "/loans", null, READERS),
          new Endpoint("GET /loans/{id}", HttpMethod.GET, "/loans/LP-001", null, READERS),
          new Endpoint(
              "GET /loans/{id}/accruals",
              HttpMethod.GET,
              "/loans/LP-001/accruals?from=2026-03-02&to=2026-03-03",
              null,
              READERS),
          new Endpoint(
              "POST /loans",
              HttpMethod.POST,
              "/loans",
              () -> WebLedger.openBody(WebLedger.HK_EQUITY, 100, "1000.00", "HKD"),
              BOOKERS),
          new Endpoint(
              "POST /loans/{id}/returns",
              HttpMethod.POST,
              "/loans/LP-001/returns",
              () -> WebLedger.returnBody(1),
              BOOKERS),
          new Endpoint(
              "GET /securities/{isin}/quote",
              HttpMethod.GET,
              "/securities/" + WebLedger.HK_EQUITY + "/quote",
              null,
              QUOTERS),
          new Endpoint(
              "GET /quotes", HttpMethod.GET, "/quotes?isin=" + WebLedger.HK_EQUITY, null, QUOTERS),
          new Endpoint("GET /audit", HttpMethod.GET, "/audit", null, Set.of("auditor")),
          // Stage 14: rollouts, operations staff only.
          new Endpoint("GET /rollouts/{flag}", HttpMethod.GET, ROLLOUT, null, Set.of("ops")),
          new Endpoint(
              "POST /rollouts/{flag}/raises",
              HttpMethod.POST,
              ROLLOUT + "/raises",
              () -> "{\"toPercent\":5}",
              Set.of("ops")),
          new Endpoint(
              "POST /rollouts/{flag}/raises/{id}/approval",
              HttpMethod.POST,
              ROLLOUT + "/raises/999999/approval",
              () -> "",
              Set.of("ops")),
          new Endpoint(
              "POST /rollouts/{flag}/lowering",
              HttpMethod.POST,
              ROLLOUT + "/lowering",
              () -> "{\"toPercent\":0}",
              Set.of("ops")));

  private static final Map<String, Supplier<String>> ROLES =
      Map.of(
          "none", () -> TestTokens.builder().build(),
          "trader", TestTokens::trader,
          "ops", TestTokens::ops,
          "counterparty", () -> TestTokens.counterparty("CP-001"),
          "auditor", TestTokens::auditor,
          "metrics", TestTokens::metrics);

  private static final List<String> ROLE_ORDER =
      List.of("none", "trader", "ops", "counterparty", "auditor", "metrics");

  private static final List<String> TABLE = new ArrayList<>();

  @BeforeAll
  static void start() {
    ledger = WebLedger.start("--ledger.marketdata.connect-timeout=200ms");
    ledger
        .jdbc
        .sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) VALUES ('LP-001', 'CP-001', ?, 1000, 1000, 'OPEN',"
                + " 1234.50, 'HKD', 'FLAT', 0.0365)")
        .param(WebLedger.HK_EQUITY)
        .update();
    TABLE.add(
        "%-30s".formatted("endpoint \\ role")
            + String.join(" ", ROLE_ORDER.stream().map(r -> "%-12s".formatted(r)).toList()));
  }

  @AfterAll
  static void stop() {
    Evidence.write(
        "13-authorization-matrix.txt",
        "Stage 13 · section 3 — each endpoint as each role (AuthorizationMatrixIT)\n"
            + "Status codes observed. 403 is the authorization refusal; any other code is the\n"
            + "endpoint's own answer once the role is allowed (404 and 504 are the fixture's: no\n"
            + "accrual rows, no market data source running).\n\n"
            + String.join("\n", TABLE)
            + "\n");
    ledger.close();
  }

  private static int call(Endpoint endpoint, String token) {
    var request = LedgerApp.http(ledger.app, token).method(endpoint.method()).uri(endpoint.path());
    if (endpoint.body() != null) {
      request.header("Idempotency-Key", UUID.randomUUID().toString());
      request.contentType(MediaType.APPLICATION_JSON);
      request.body(endpoint.body().get());
    }
    return request.exchange().returnResult(String.class).getStatus().value();
  }

  @Test
  @DisplayName("each role can call exactly the endpoints the model says, and is refused the rest")
  void matrix() {
    for (Endpoint endpoint : ENDPOINTS) {
      StringBuilder row = new StringBuilder("%-30s".formatted(endpoint.name()));
      for (String role : ROLE_ORDER) {
        int status = call(endpoint, ROLES.get(role).get());
        row.append("%-13d".formatted(status));
        if (endpoint.allowed().contains(role)) {
          assertThat(status).as("%s as %s", endpoint.name(), role).isNotIn(401, 403);
        } else {
          assertThat(status).as("%s as %s", endpoint.name(), role).isEqualTo(403);
        }
      }
      TABLE.add(row.toString());
    }
  }

  @Test
  @DisplayName("a refusal is the same body whatever the role would have needed")
  void refusalNamesNoRole() {
    var result =
        LedgerApp.http(ledger.app, TestTokens.metrics())
            .get()
            .uri("/loans")
            .exchange()
            .returnResult(String.class);

    assertThat(result.getStatus().value()).isEqualTo(403);
    assertThat(result.getResponseBody())
        .contains("urn:sl-ledger:problem:forbidden")
        .doesNotContainIgnoringCase("ledger-trader")
        .doesNotContainIgnoringCase("role");
  }
}
