package com.jerome.slledger.bootstrap.web;

import com.jerome.slledger.bootstrap.position.LedgerApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/** The read endpoints, against the running application and Postgres. */
class LoanReadIT {

  private static ConfigurableApplicationContext app;
  private static RestTestClient http;

  @BeforeAll
  static void start() {
    app = LedgerApp.startWeb();
    http = LedgerApp.http(app);
    JdbcClient jdbc = app.getBean(JdbcClient.class);
    LedgerApp.referenceData(jdbc);
    jdbc.sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) SELECT 'LP-' || lpad(n::text, 3, '0'), 'CP-001',"
                + " 'HK0000000001', 1000, 1000, 'OPEN', 1234.50, 'HKD', 'FLAT', 0.0365"
                + " FROM generate_series(1, 25) n")
        .update();
    jdbc.sql(
            "INSERT INTO accrual_entry (position_id, business_date, fee_amount, fee_currency)"
                + " SELECT 'LP-001', date '2026-03-02' + d, 0.34, 'HKD'"
                + " FROM generate_series(0, 9) d")
        .update();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  @Test
  @DisplayName("GET /loans pages by offset, with the total, amounts as strings")
  void listsByOffset() {
    http.get()
        .uri("/loans?page=2&size=10")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.page")
        .isEqualTo(2)
        .jsonPath("$.size")
        .isEqualTo(10)
        .jsonPath("$.totalRows")
        .isEqualTo(25)
        .jsonPath("$.rows.length()")
        .isEqualTo(5)
        .jsonPath("$.rows[0].id")
        .isEqualTo("LP-021")
        .jsonPath("$.rows[0].collateral.amount")
        .isEqualTo("1234.50")
        .jsonPath("$.rows[0].collateral.currency")
        .isEqualTo("HKD");
  }

  @Test
  @DisplayName("a page size above the cap, or a negative page, is a 400")
  void pageBounds() {
    http.get().uri("/loans?size=201").exchange().expectStatus().isBadRequest();
    http.get().uri("/loans?page=-1").exchange().expectStatus().isBadRequest();
  }

  @Test
  @DisplayName("GET /loans/{id} shows one position")
  void showsOne() {
    http.get()
        .uri("/loans/LP-007")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json(
            """
            {"id":"LP-007","counterpartyId":"CP-001","isin":"HK0000000001","status":"OPEN",
             "originalQuantity":1000,"outstandingQuantity":1000,
             "collateral":{"amount":"1234.50","currency":"HKD"}}
            """);
  }

  @Test
  @DisplayName("an unknown position is a 404 ProblemDetail")
  void unknownIs404() {
    http.get()
        .uri("/loans/LP-999")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("urn:sl-ledger:problem:not-found");
    http.get()
        .uri("/loans/LP-999/accruals?from=2026-03-01&to=2026-03-31")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  @DisplayName("GET /loans/{id}/accruals returns the range, both ends inclusive")
  void listsAccruals() {
    http.get()
        .uri("/loans/LP-001/accruals?from=2026-03-03&to=2026-03-05")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json(
            """
            {"positionId":"LP-001","from":"2026-03-03","to":"2026-03-05","entries":[
              {"businessDate":"2026-03-03","fee":{"amount":"0.34","currency":"HKD"}},
              {"businessDate":"2026-03-04","fee":{"amount":"0.34","currency":"HKD"}},
              {"businessDate":"2026-03-05","fee":{"amount":"0.34","currency":"HKD"}}]}
            """);
  }

  @Test
  @DisplayName("a reversed range, a range over 366 days, or a bad date is a 400")
  void badRanges() {
    http.get()
        .uri("/loans/LP-001/accruals?from=2026-03-05&to=2026-03-03")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("urn:sl-ledger:problem:invalid-query");
    http.get()
        .uri("/loans/LP-001/accruals?from=2025-01-01&to=2026-01-02")
        .exchange()
        .expectStatus()
        .isBadRequest();
    http.get()
        .uri("/loans/LP-001/accruals?from=2024-01-01&to=2024-12-31")
        .exchange()
        .expectStatus()
        .isOk();
    http.get()
        .uri("/loans/LP-001/accruals?from=yesterday&to=2026-03-03")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
