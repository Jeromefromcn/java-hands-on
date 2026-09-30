package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static com.jerome.slledger.bootstrap.web.WebLedger.US_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.bootstrap.config.CachedQuoteSources;
import com.jerome.slledger.domain.instrument.Isin;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * {@code GET /securities/{isin}/quote} with the cache on, against a real Redis and a stub source.
 */
@Timeout(60)
class CachedQuoteIT {

  private static StubPriceServer source;
  private static WebLedger ledger;
  private static SharedRedis.Client redis;

  @BeforeAll
  static void start() {
    SharedRedis.start();
    source = StubPriceServer.start();
    redis = SharedRedis.client();
    ledger =
        WebLedger.start(
            "--ledger.cache.enabled=true",
            "--spring.data.redis.host=" + SharedRedis.host(),
            "--spring.data.redis.port=" + SharedRedis.port(),
            "--ledger.marketdata.base-url=" + source.baseUrl(),
            "--ledger.marketdata.read-timeout=500ms");
  }

  @AfterAll
  static void stop() {
    ledger.close();
    source.close();
    redis.close();
  }

  @AfterEach
  void flush() {
    SharedRedis.flushAll();
    CachedQuoteSources sources = ledger.app.getBean(CachedQuoteSources.class);
    sources.prices().evictLocal(new Isin(HK_EQUITY));
    sources.prices().evictLocal(new Isin(US_EQUITY));
  }

  @Test
  @DisplayName("the second quote is served from the cache, and the amount is unchanged on the wire")
  void secondQuoteDoesNotReachTheSource() {
    source.price(HK_EQUITY, "41.30", "HKD");
    int before = source.arrived();

    EntityExchangeResult<String> first = get(HK_EQUITY);
    EntityExchangeResult<String> second = get(HK_EQUITY);

    String expected =
        "{\"isin\":\"%s\",\"price\":{\"amount\":\"41.30\",\"currency\":\"HKD\"}}"
            .formatted(HK_EQUITY);
    assertThat(first.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(first.getResponseBody()).isEqualTo(expected);
    assertThat(second.getResponseBody()).isEqualTo(expected);
    assertThat(source.arrived() - before).isEqualTo(1);
  }

  @Test
  @DisplayName("Redis holds the price as JSON with a string amount, and a TTL")
  void redisHoldsTheJson() {
    source.price(HK_EQUITY, "41.30", "HKD");

    get(HK_EQUITY);

    String key = "sl:price:" + HK_EQUITY;
    assertThat(redis.template().opsForValue().get(key))
        .isEqualTo("{\"state\":\"present\",\"amount\":\"41.30\",\"currency\":\"HKD\"}");
    assertThat(redis.template().getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 42_000L);
  }

  @Test
  @DisplayName("a security the source does not know costs the source one call, not one per request")
  void unknownToSourceIsCached() {
    source.answer(US_EQUITY, new StubPriceServer.Json(404, "{}"));
    int before = source.arrived();

    for (int i = 0; i < 20; i++) {
      assertThat(get(US_EQUITY).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    assertThat(source.arrived() - before).isEqualTo(1);
  }

  @Test
  @DisplayName("an ISIN the ledger does not hold costs one directory read, not one per request")
  void unknownToLedgerIsCached() {
    CachedQuoteSources sources = ledger.app.getBean(CachedQuoteSources.class);
    long before = sources.securities().counters().loads();

    for (int i = 0; i < 20; i++) {
      assertThat(get("HK0000000023").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    assertThat(sources.securities().counters().loads() - before).isEqualTo(1);
  }

  @Test
  @DisplayName("a source outage is not remembered: the next quote reaches the source again")
  void outageIsNotCached() {
    source.answer(HK_EQUITY, new StubPriceServer.Json(500, "{}"));
    assertThat(get(HK_EQUITY).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);

    source.price(HK_EQUITY, "41.30", "HKD");

    assertThat(get(HK_EQUITY).getStatus()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("a cached price in the wrong currency is still refused")
  void wrongCurrencyIsRefusedFromTheCacheToo() {
    source.price(HK_EQUITY, "5.30", "USD");
    int before = source.arrived();

    assertThat(get(HK_EQUITY).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(get(HK_EQUITY).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);

    assertThat(source.arrived() - before).isEqualTo(1);
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
