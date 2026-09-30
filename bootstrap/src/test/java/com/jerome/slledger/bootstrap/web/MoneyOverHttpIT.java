package com.jerome.slledger.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * The part B check the roadmap asks for: an amount sent over HTTP, through JPA into Postgres and
 * back out over HTTP, keeps its value, its scale and its currency. Currencies of scale 2, 0 and 3,
 * at the 16 integer digits {@code numeric(22,6)} allows.
 */
class MoneyOverHttpIT {

  private static WebLedger ledger;

  @BeforeAll
  static void start() {
    ledger = WebLedger.start();
    ledger
        .jdbc
        .sql(
            "INSERT INTO counterparty VALUES"
                + " ('CP-HKD', 'Synthetic HKD Borrower', 9999999999999999.99, 'HKD'),"
                + " ('CP-JPY', 'Synthetic JPY Borrower', 9999999999999999, 'JPY'),"
                + " ('CP-BHD', 'Synthetic BHD Borrower', 9999999999999999.999, 'BHD')")
        .update();
    ledger
        .jdbc
        .sql(
            "INSERT INTO security VALUES"
                + " ('JP0000000018', 'Synthetic JP Equity', 'JPY', 1500, 'JPY'),"
                + " ('BH0000000016', 'Synthetic BH Equity', 'BHD', 1.250, 'BHD')")
        .update();
  }

  @AfterAll
  static void stop() {
    ledger.close();
  }

  @ParameterizedTest(name = "{2} {3}")
  @CsvSource({
    "CP-HKD, HK0000000007, 9999999999999999.99, HKD, 41.26",
    "CP-JPY, JP0000000018, 9999999999999999, JPY, 1500",
    "CP-BHD, BH0000000016, 9999999999999999.999, BHD, 1.250"
  })
  @DisplayName("an amount survives HTTP, JPA, Postgres and the way back exactly")
  void roundTrip(String counterparty, String isin, String amount, String currency, String price) {
    EntityExchangeResult<String> opened =
        open("K-" + currency, counterparty, isin, "\"" + amount + "\"", currency);
    assertThat(opened.getStatus()).isEqualTo(HttpStatus.CREATED);
    assertThat(opened.getResponseBody())
        .contains(
            "\"unitPrice\":{\"amount\":\"%s\",\"currency\":\"%s\"}".formatted(price, currency));
    String id = opened.getResponseHeaders().getLocation().toString().substring("/loans/".length());

    String shown =
        ledger
            .http
            .get()
            .uri("/loans/" + id)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(shown)
        .contains(
            "\"collateral\":{\"amount\":\"%s\",\"currency\":\"%s\"}".formatted(amount, currency));
    Map<String, Object> row =
        ledger
            .jdbc
            .sql(
                "SELECT collateral_amount, collateral_currency, pg_typeof(collateral_amount)::text"
                    + " AS type FROM loan_position WHERE id = ?")
            .param(id)
            .query()
            .singleRow();
    assertThat((BigDecimal) row.get("collateral_amount")).isEqualByComparingTo(amount);
    assertThat(row.get("collateral_currency")).isEqualTo(currency);
    assertThat(row.get("type")).isEqualTo("numeric");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "a number, not a string | 50000.00 | HKD",
        "decimals JPY does not have | \"1500.5\" | JPY",
        "a fourth decimal for BHD | \"1.2345\" | BHD"
      })
  @DisplayName("an amount that would be rounded on the way in is refused, and nothing is booked")
  void refusedAmounts(String description, String amountJson, String currency) {
    long before = ledger.count("loan_trade");

    EntityExchangeResult<String> refused =
        open("K-bad-" + currency, "CP-" + currency, isinFor(currency), amountJson, currency);

    assertThat(refused.getStatus()).as(description).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(refused.getResponseBody()).contains("urn:sl-ledger:problem:malformed-body");
    assertThat(ledger.count("loan_trade")).isEqualTo(before);
  }

  private static String isinFor(String currency) {
    return switch (currency) {
      case "JPY" -> "JP0000000018";
      case "BHD" -> "BH0000000016";
      default -> WebLedger.HK_EQUITY;
    };
  }

  private static EntityExchangeResult<String> open(
      String key, String counterparty, String isin, String amountJson, String currency) {
    String body =
        """
        {"counterpartyId":"%s","isin":"%s","quantity":1000,
         "collateral":{"amount":%s,"currency":"%s"},
         "rule":{"type":"FLAT","rate":"0.0365"},"tradeDate":"2026-09-01"}
        """
            .formatted(counterparty, isin, amountJson, currency);
    return ledger
        .http
        .post()
        .uri("/loans")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(String.class)
        .returnResult();
  }
}
