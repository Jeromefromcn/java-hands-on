package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY_2;
import static com.jerome.slledger.bootstrap.web.WebLedger.US_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/** {@code GET /securities/{isin}/quote} against a stub source that answers as each test needs. */
@Timeout(60)
class QuoteIT {

  private static StubPriceServer source;
  private static WebLedger ledger;

  @BeforeAll
  static void start() {
    source = StubPriceServer.start();
    ledger =
        WebLedger.start(
            "--ledger.marketdata.base-url=" + source.baseUrl(),
            "--ledger.marketdata.read-timeout=500ms");
  }

  @AfterAll
  static void stop() {
    ledger.close();
    source.close();
  }

  @Test
  @DisplayName("a price from the source is passed on with its amount as a string")
  void quotes() {
    source.price(HK_EQUITY, "41.30", "HKD");

    EntityExchangeResult<String> quote = get(HK_EQUITY);

    assertThat(quote.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(quote.getResponseBody())
        .isEqualTo(
            "{\"isin\":\"%s\",\"price\":{\"amount\":\"41.30\",\"currency\":\"HKD\"}}"
                .formatted(HK_EQUITY));
  }

  @Test
  @DisplayName("a security the ledger does not know is a 404 without calling the source")
  void unknownToLedger() {
    int before = source.arrived();

    assertThat(get("HK0000000023").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(source.arrived()).isEqualTo(before);
  }

  @Test
  @DisplayName("a security the source does not know is a 404")
  void unknownToSource() {
    source.answer(US_EQUITY, new StubPriceServer.Json(404, "{}"));

    assertThat(get(US_EQUITY).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("a source error, an over-precise price, a number, a wrong currency or ISIN: 502")
  void rejected() {
    assertRejected(new StubPriceServer.Json(500, "{\"error\":\"down\"}"));
    assertRejected(price("41.305", "HKD"));
    assertRejected(
        new StubPriceServer.Json(
            200,
            "{\"isin\":\"%s\",\"price\":{\"amount\":41.30,\"currency\":\"HKD\"}}"
                .formatted(HK_EQUITY_2)));
    // Review focus 6: a well-formed price in a currency the security does not trade in.
    assertRejected(price("5.30", "USD"));
    assertRejected(
        new StubPriceServer.Json(
            200,
            "{\"isin\":\"HK0000000007\",\"price\":{\"amount\":\"7.50\",\"currency\":\"HKD\"}}"));
  }

  @Test
  @DisplayName("a source that hangs is a 504 within the read timeout")
  void hangIsGatewayTimeout() {
    source.hang(HK_EQUITY_2);
    try {
      long started = System.nanoTime();
      EntityExchangeResult<String> quote = get(HK_EQUITY_2);
      Duration took = Duration.ofNanos(System.nanoTime() - started);

      assertThat(quote.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
      assertThat(quote.getResponseBody()).contains("urn:sl-ledger:problem:market-data-unavailable");
      assertThat(took).isLessThan(Duration.ofSeconds(2));
    } finally {
      source.release();
    }
  }

  @Test
  @DisplayName("a malformed ISIN is refused before anything is looked up")
  void malformedIsin() {
    assertThat(get("HK0000000001").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private static StubPriceServer.Json price(String amount, String currency) {
    return new StubPriceServer.Json(
        200,
        "{\"isin\":\"%s\",\"price\":{\"amount\":\"%s\",\"currency\":\"%s\"}}"
            .formatted(HK_EQUITY_2, amount, currency));
  }

  private static void assertRejected(StubPriceServer.Json answer) {
    source.answer(HK_EQUITY_2, answer);
    EntityExchangeResult<String> quote = get(HK_EQUITY_2);
    assertThat(quote.getStatus()).as(answer.body()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(quote.getResponseBody()).contains("urn:sl-ledger:problem:market-data-rejected");
  }

  private static EntityExchangeResult<String> get(String isin) {
    return ledger
        .http
        .get()
        .uri("/securities/{isin}/quote", isin)
        .exchange()
        .expectBody(String.class)
        .returnResult();
  }
}
