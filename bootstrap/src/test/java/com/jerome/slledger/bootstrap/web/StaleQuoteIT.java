package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY_2;
import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * A quote when the source goes away: the last price, marked stale, and never silently. The breaker
 * opens after five failed calls here, so the later requests do not touch the source at all.
 */
@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaleQuoteIT {

  // The tests share one context and so one breaker: the first opens it, the others read the state
  // it leaves, which is the point. Their order is therefore fixed.

  private static StubPriceServer source;
  private static WebLedger ledger;

  @BeforeAll
  static void start() {
    source = StubPriceServer.start();
    ledger =
        WebLedger.start(
            "--ledger.marketdata.base-url=" + source.baseUrl(),
            "--ledger.marketdata.read-timeout=500ms",
            "--ledger.resilience.marketdata.breaker.window-size=10",
            "--ledger.resilience.marketdata.breaker.minimum-calls=5",
            "--ledger.resilience.marketdata.breaker.open-wait=60s");
  }

  @AfterAll
  static void stop() {
    ledger.close();
    source.close();
  }

  private static EntityExchangeResult<String> get(String path) {
    return ledger.http.get().uri(path).exchange().returnResult(String.class);
  }

  @Test
  @Order(1)
  @DisplayName("a quote the source cannot give is the last price, flagged stale with its time")
  void staleWhenTheSourceIsDown() {
    source.price(HK_EQUITY, "41.30", "HKD");
    EntityExchangeResult<String> live = get("/securities/" + HK_EQUITY + "/quote");
    assertThat(live.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(live.getResponseBody()).doesNotContain("stale").doesNotContain("asOf");
    assertThat(live.getResponseHeaders().getFirst("Warning")).isNull();

    source.answer(HK_EQUITY, new StubPriceServer.Json(500, "{}"));
    // Five failures open the breaker; every one of them is already answered from the last price.
    EntityExchangeResult<String> stale = null;
    for (int i = 0; i < 8; i++) {
      stale = get("/securities/" + HK_EQUITY + "/quote");
      assertThat(stale.getStatus()).isEqualTo(HttpStatus.OK);
    }

    assertThat(stale.getResponseBody())
        .contains("\"stale\":true")
        .contains("\"asOf\":\"")
        .contains("\"amount\":\"41.30\"");
    assertThat(stale.getResponseHeaders().getFirst("Warning"))
        .isEqualTo("110 - \"Response is Stale\"");
    Evidence.write(
        "12-stale-quote.txt",
        "live : "
            + live.getResponseBody()
            + "\nstale: "
            + stale.getResponseBody()
            + "\nWarning: "
            + stale.getResponseHeaders().getFirst("Warning")
            + "\n");
  }

  @Test
  @Order(2)
  @DisplayName("a security never priced has nothing to fall back to: the stage 7 504")
  void noStaleWithoutAHistory() {
    EntityExchangeResult<String> result = get("/securities/" + HK_EQUITY_2 + "/quote");

    assertThat(result.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
  }

  @Test
  @Order(3)
  @DisplayName("/quotes marks only the degraded items stale")
  void quotesMarksItemsIndividually() {
    // The breaker is still open from the first test. HK_EQUITY has a history, HK_EQUITY_2 has not.
    String body = get("/quotes?isin=" + HK_EQUITY + "&isin=" + HK_EQUITY_2).getResponseBody();

    assertThat(body).contains("\"stale\":true");
    assertThat(body).contains("market-data-unavailable");
  }

  @Test
  @Order(4)
  @DisplayName("a stale quote never feeds lending: an open still prices from the security row")
  void staleQuoteNeverFeedsLending() {
    // The breaker is open and the last-known price is 41.30; the security row says 41.26.
    String response =
        ledger
            .http
            .post()
            .uri("/loans")
            .header("Idempotency-Key", "stale-never-feeds-lending")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(WebLedger.openBody(HK_EQUITY, 1000, "100000.00", "HKD"))
            .exchange()
            .returnResult(String.class)
            .getStatus()
            .toString();

    assertThat(response).startsWith("201");
    assertThat(
            ledger
                .jdbc
                .sql("SELECT unit_price_amount FROM loan_trade")
                .query(java.math.BigDecimal.class)
                .single())
        .isEqualByComparingTo("41.26");
  }
}
