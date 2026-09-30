package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY_2;
import static com.jerome.slledger.bootstrap.web.WebLedger.US_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.jayway.jsonpath.JsonPath;
import com.jerome.slledger.bootstrap.config.MarketDataPool;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/** {@code GET /quotes}: per-security outcomes, the cap, and the executor's refusal as a 503. */
@Timeout(60)
class QuotesIT {

  @Test
  @DisplayName(
      "each item carries its own outcome; a source 404 is that item's error, not the request's")
  void partialFailure() {
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger = WebLedger.start("--ledger.marketdata.base-url=" + source.baseUrl())) {
      source.price(HK_EQUITY, "41.30", "HKD");
      source.answer(HK_EQUITY_2, new StubPriceServer.Json(404, "{}"));

      EntityExchangeResult<String> result = get(ledger, HK_EQUITY, HK_EQUITY_2);

      assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
      assertThat(result.getResponseBody())
          .isEqualTo(
              ("{\"quotes\":[{\"isin\":\"%s\",\"price\":{\"amount\":\"41.30\",\"currency\":\"HKD\"}},"
                      + "{\"isin\":\"%s\",\"error\":{\"type\":\"urn:sl-ledger:problem:not-found\"}}]}")
                  .formatted(HK_EQUITY, HK_EQUITY_2));
    }
  }

  @Test
  @DisplayName(
      "a batch in two currencies keeps each price in its own; nothing is added or converted")
  void mixedCurrencies() {
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger = WebLedger.start("--ledger.marketdata.base-url=" + source.baseUrl())) {
      source.price(HK_EQUITY, "41.30", "HKD");
      source.price(US_EQUITY, "10.05", "USD");

      String body = get(ledger, HK_EQUITY, US_EQUITY).getResponseBody();

      assertThat(JsonPath.<String>read(body, "$.quotes[0].price.currency")).isEqualTo("HKD");
      assertThat(JsonPath.<String>read(body, "$.quotes[1].price.currency")).isEqualTo("USD");
      assertThat(JsonPath.<String>read(body, "$.quotes[1].price.amount")).isEqualTo("10.05");
      assertThat(body).doesNotContain("total");
    }
  }

  @Test
  @DisplayName("more ISINs than the cap is a 400 and the source is never called")
  void overTheCap() {
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger =
            WebLedger.start(
                "--ledger.marketdata.base-url=" + source.baseUrl(),
                "--ledger.quotes.max-isins=2")) {
      EntityExchangeResult<String> result = get(ledger, HK_EQUITY, HK_EQUITY_2, US_EQUITY);

      assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(source.arrived()).isZero();
    }
  }

  @Test
  @DisplayName(
      "when the executor refuses every security the request is a 503 that matches the contract")
  void overloadedIs503() throws Exception {
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger =
            WebLedger.start(
                "--ledger.marketdata.base-url=" + source.baseUrl(),
                "--ledger.marketdata.executor.core=1",
                "--ledger.marketdata.executor.max=1",
                "--ledger.marketdata.executor.queue=1")) {
      source.hang(HK_EQUITY);
      source.hang(HK_EQUITY_2);
      // One task runs and hangs in the source, one waits in the queue: the pool is full.
      CompletableFuture<EntityExchangeResult<String>> first =
          CompletableFuture.supplyAsync(() -> get(ledger, HK_EQUITY, HK_EQUITY_2));
      try {
        awaitTrue(
            () -> {
              var stats = ledger.app.getBean(MarketDataPool.class).stats();
              return stats.active() == 1 && stats.queued() == 1;
            });

        EntityExchangeResult<String> refused = get(ledger, US_EQUITY);

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(JsonPath.<String>read(refused.getResponseBody(), "$.type"))
            .isEqualTo("urn:sl-ledger:problem:overloaded");
        String document =
            ledger
                .http
                .get()
                .uri("/v3/api-docs")
                .exchange()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        var report =
            OpenApiInteractionValidator.createForInlineApiSpecification(document)
                .build()
                .validateResponse(
                    "/quotes",
                    Request.Method.GET,
                    SimpleResponse.Builder.status(503)
                        .withContentType(
                            String.valueOf(refused.getResponseHeaders().getContentType()))
                        .withBody(refused.getResponseBody())
                        .build());
        assertThat(report.getMessages()).isEmpty();
      } finally {
        source.release();
      }
      assertThat(first.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(HttpStatus.OK);
    }
  }

  private static EntityExchangeResult<String> get(WebLedger ledger, String... isins) {
    return ledger
        .http
        .get()
        .uri(
            uri -> {
              uri.path("/quotes");
              for (String isin : isins) {
                uri.queryParam("isin", isin);
              }
              return uri.build();
            })
        .exchange()
        .expectBody(String.class)
        .returnResult();
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached");
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
  }
}
