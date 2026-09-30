package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.config.CachedQuoteSources;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;

/** Without {@code ledger.cache.enabled}, the quote path is exactly stage 7's. */
@Timeout(60)
class CacheDisabledIT {

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
  @DisplayName("no cache beans exist and every quote reaches the source")
  void everyQuoteReachesTheSource() {
    source.price(HK_EQUITY, "41.30", "HKD");
    int before = source.arrived();

    for (int i = 0; i < 3; i++) {
      var result =
          ledger
              .http
              .get()
              .uri("/securities/{isin}/quote", HK_EQUITY)
              .exchange()
              .expectBody(String.class)
              .returnResult();
      assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
    }

    assertThat(ledger.app.getBeansOfType(CachedQuoteSources.class)).isEmpty();
    assertThat(source.arrived() - before).isEqualTo(3);
  }
}
