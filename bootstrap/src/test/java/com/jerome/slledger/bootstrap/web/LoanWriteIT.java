package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static com.jerome.slledger.bootstrap.web.WebLedger.openBody;
import static com.jerome.slledger.bootstrap.web.WebLedger.returnBody;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/** {@code POST /loans} and {@code POST /loans/{id}/returns}, against the running application. */
class LoanWriteIT {

  private WebLedger ledger;

  @BeforeEach
  void start() {
    ledger = WebLedger.start();
  }

  @AfterEach
  void stop() {
    ledger.close();
  }

  @Test
  @DisplayName("an open answers 201 with a Location that shows the new position")
  void openThenGetLocation() {
    EntityExchangeResult<String> opened =
        post("/loans", "K-1", openBody(HK_EQUITY, 1000, "50000.00", "HKD"));

    assertThat(opened.getStatus()).isEqualTo(HttpStatus.CREATED);
    String location = opened.getResponseHeaders().getLocation().toString();
    assertThat(location).startsWith("/loans/");
    assertThat(opened.getResponseHeaders().get("Idempotent-Replayed")).isNull();
    assertThat(opened.getResponseBody())
        .contains(
            "\"trade\":{\"direction\":\"BORROW\",\"quantity\":1000,"
                + "\"unitPrice\":{\"amount\":\"41.26\",\"currency\":\"HKD\"},"
                + "\"tradeDate\":\"2026-09-01\",\"settlementDate\":\"2026-09-03\"}");

    ledger
        .http
        .get()
        .uri(location)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("OPEN")
        .jsonPath("$.collateral.amount")
        .isEqualTo("50000.00");
  }

  @Test
  @DisplayName("a replay is byte-identical and says it is a replay")
  void replayIsIdentical() {
    EntityExchangeResult<String> first =
        post("/loans", "K-1", openBody(HK_EQUITY, 1000, "50000.00", "HKD"));
    // Same request, different JSON layout and amount scale: the fingerprint is of the command.
    EntityExchangeResult<String> second =
        post(
            "/loans",
            "K-1",
            "{\"tradeDate\":\"2026-09-01\",\"rule\":{\"rate\":\"0.0365\",\"type\":\"FLAT\"},"
                + "\"collateral\":{\"currency\":\"HKD\",\"amount\":\"50000.0\"},"
                + "\"quantity\":1000,\"isin\":\""
                + HK_EQUITY
                + "\",\"counterpartyId\":\"CP-001\"}");

    assertThat(second.getStatus()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getResponseBody()).isEqualTo(first.getResponseBody());
    assertThat(second.getResponseHeaders().getLocation())
        .isEqualTo(first.getResponseHeaders().getLocation());
    assertThat(second.getResponseHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(ledger.count("loan_position")).isEqualTo(1);
    assertThat(ledger.count("loan_trade")).isEqualTo(1);
  }

  @Test
  @DisplayName("a missing Idempotency-Key is a 400 and books nothing")
  void missingKey() {
    ledger
        .http
        .post()
        .uri("/loans")
        .contentType(MediaType.APPLICATION_JSON)
        .body(openBody(HK_EQUITY, 1000, "50000.00", "HKD"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("urn:sl-ledger:problem:missing-header");
    assertThat(ledger.count("loan_trade")).isZero();
  }

  @Test
  @DisplayName("the same key with a different body is a 422")
  void sameKeyDifferentBody() {
    post("/loans", "K-1", openBody(HK_EQUITY, 1000, "50000.00", "HKD"));

    EntityExchangeResult<String> reused =
        post("/loans", "K-1", openBody(HK_EQUITY, 999, "50000.00", "HKD"));

    assertThat(reused.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(reused.getResponseBody()).contains("urn:sl-ledger:problem:idempotency-key-reused");
    assertThat(ledger.count("loan_position")).isEqualTo(1);
  }

  /** Review focus 2. */
  @Test
  @DisplayName("an open's key reused for a return is a 422, not a replay of the open")
  void opensKeyForReturn() {
    String id = positionOf(post("/loans", "K-1", openBody(HK_EQUITY, 1000, "50000.00", "HKD")));

    EntityExchangeResult<String> reused = post("/loans/" + id + "/returns", "K-1", returnBody(10));

    assertThat(reused.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(reused.getResponseBody()).contains("idempotency-key-reused");
  }

  @Test
  @DisplayName("a partial and then a full return close the position")
  void returnsClosePosition() {
    String id = positionOf(post("/loans", "K-1", openBody(HK_EQUITY, 1000, "50000.00", "HKD")));

    EntityExchangeResult<String> partial =
        post("/loans/" + id + "/returns", "K-2", returnBody(400));
    EntityExchangeResult<String> full = post("/loans/" + id + "/returns", "K-3", returnBody(600));

    assertThat(partial.getStatus()).isEqualTo(HttpStatus.CREATED);
    assertThat(partial.getResponseHeaders().getLocation()).isNull();
    assertThat(partial.getResponseBody()).contains("\"direction\":\"RETURN\",\"quantity\":400");
    assertThat(full.getStatus()).isEqualTo(HttpStatus.CREATED);
    ledger
        .http
        .get()
        .uri("/loans/" + id)
        .exchange()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("CLOSED")
        .jsonPath("$.outstandingQuantity")
        .isEqualTo(0);
  }

  @Test
  @DisplayName("business refusals are 422 with their kind; an unknown position is 404")
  void refusals() {
    EntityExchangeResult<String> overLimit =
        post("/loans", "K-1", openBody(HK_EQUITY, 1000, "1000000.01", "HKD"));
    assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(overLimit.getResponseBody()).contains("urn:sl-ledger:problem:insufficient-credit");

    String id = positionOf(post("/loans", "K-2", openBody(HK_EQUITY, 1000, "50000.00", "HKD")));
    EntityExchangeResult<String> tooMany =
        post("/loans/" + id + "/returns", "K-3", returnBody(1001));
    assertThat(tooMany.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(tooMany.getResponseBody()).contains("urn:sl-ledger:problem:excessive-return");

    EntityExchangeResult<String> unknown = post("/loans/LP-NONE/returns", "K-4", returnBody(1));
    assertThat(unknown.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);

    EntityExchangeResult<String> unknownCounterparty =
        post(
            "/loans",
            "K-5",
            openBody(HK_EQUITY, 1000, "50000.00", "HKD").replace("CP-001", "CP-404"));
    assertThat(unknownCounterparty.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(unknownCounterparty.getResponseBody()).contains("unknown-reference");
    assertThat(ledger.count("loan_trade")).isEqualTo(1);
  }

  @Test
  @DisplayName("invalid fields are a 400 naming each field, and nothing is booked")
  void validation() {
    String valid = openBody(HK_EQUITY, 1000, "50000.00", "HKD");
    assertInvalid(valid.replace(HK_EQUITY, "HK0000000001"), "isin");
    assertInvalid(valid.replace("\"rate\":\"0.0365\"", "\"rate\":\"0.2500\""), "rule.rate");
    assertInvalid(valid.replace("\"quantity\":1000", "\"quantity\":0"), "quantity");
    assertInvalid(valid.replace("\"amount\":\"50000.00\"", "\"amount\":\"-1.00\""), "collateral");
    assertInvalid(valid.replace(",\"tradeDate\":\"2026-09-01\"", ""), "tradeDate");
    assertInvalid(valid.replace("\"counterpartyId\":\"CP-001\",", ""), "counterpartyId");

    String id = positionOf(post("/loans", "K-OK", valid));
    EntityExchangeResult<String> zeroReturn =
        post("/loans/" + id + "/returns", "K-R", returnBody(0));
    assertThat(zeroReturn.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(zeroReturn.getResponseBody()).contains("\"field\":\"quantity\"");
    assertThat(ledger.count("loan_trade")).isEqualTo(1);
  }

  @Test
  @DisplayName("a rule type the ledger cannot persist is a 400, not a 500")
  void unknownRuleType() {
    EntityExchangeResult<String> floating =
        post(
            "/loans",
            "K-1",
            openBody(HK_EQUITY, 1000, "50000.00", "HKD").replace("\"FLAT\"", "\"FLOATING\""));

    assertThat(floating.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(floating.getResponseBody()).contains("urn:sl-ledger:problem:malformed-body");
  }

  @Test
  @DisplayName("an Idempotency-Key with a space or over 255 characters is a 400")
  void badKeys() {
    String body = openBody(HK_EQUITY, 1000, "50000.00", "HKD");
    assertThat(post("/loans", "has space", body).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/loans", "k".repeat(256), body).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/loans", "k".repeat(255), body).getStatus()).isEqualTo(HttpStatus.CREATED);
  }

  private void assertInvalid(String body, String field) {
    EntityExchangeResult<String> refused = post("/loans", "K-" + field, body);
    assertThat(refused.getStatus()).as(field).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(refused.getResponseBody())
        .as(field)
        .contains("urn:sl-ledger:problem:validation")
        .contains("\"field\":\"" + field + "\"");
  }

  private EntityExchangeResult<String> post(String uri, String key, String body) {
    return ledger
        .http
        .post()
        .uri(uri)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(String.class)
        .returnResult();
  }

  private static String positionOf(EntityExchangeResult<String> opened) {
    String location = opened.getResponseHeaders().getLocation().toString();
    return location.substring("/loans/".length());
  }
}
