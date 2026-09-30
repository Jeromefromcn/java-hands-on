package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.messaging.testing.SharedKafka;
import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.messaging.ApplyPriceUpdate;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.bootstrap.config.CachedQuoteSources;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A stored price evicts the cached quote, and only once it has committed: the in-process {@code
 * PriceApplied} event registered for {@code AFTER_COMMIT}. An update whose transaction rolls back
 * evicts nothing, because nothing changed.
 */
@Timeout(120)
class PriceUpdateEvictsCacheIT {

  private static final Currency HKD = Currency.getInstance("HKD");

  private static StubPriceServer source;
  private static WebLedger ledger;
  private static long version = 100;

  @BeforeAll
  static void start() {
    SharedRedis.start();
    SharedKafka.start();
    source = StubPriceServer.start();
    ledger =
        WebLedger.start(
            "--ledger.cache.enabled=true",
            "--spring.data.redis.host=" + SharedRedis.host(),
            "--spring.data.redis.port=" + SharedRedis.port(),
            "--ledger.marketdata.base-url=" + source.baseUrl(),
            "--ledger.messaging.enabled=true",
            "--ledger.messaging.bootstrap-servers=" + SharedKafka.bootstrapServers(),
            "--ledger.messaging.relay-enabled=false",
            "--ledger.messaging.consumers-enabled=false");
  }

  @AfterAll
  static void stop() {
    ledger.close();
    source.close();
  }

  @AfterEach
  void flush() {
    SharedRedis.flushAll();
    ledger.app.getBean(CachedQuoteSources.class).prices().evictLocal(new Isin(HK_EQUITY));
  }

  private static void quote() {
    ledger.http.get().uri("/securities/{isin}/quote", HK_EQUITY).exchange().expectStatus().isOk();
  }

  private static PriceUpdated update(String price) {
    return new PriceUpdated(
        UUID.randomUUID(), Instant.now(), new Isin(HK_EQUITY), Money.of(price, HKD), ++version);
  }

  @Test
  @DisplayName(
      "a committed price update evicts the cached quote; the next quote reaches the source")
  void committedUpdateEvicts() {
    source.price(HK_EQUITY, "41.30", "HKD");
    int before = source.arrived();
    quote();
    quote();
    assertThat(source.arrived() - before).isEqualTo(1);

    ledger.app.getBean(ApplyPriceUpdate.class).apply(update("42.00"));

    quote();
    assertThat(source.arrived() - before).isEqualTo(2);
  }

  @Test
  @DisplayName("an update that rolls back evicts nothing")
  void rolledBackUpdateDoesNotEvict() {
    source.price(HK_EQUITY, "41.30", "HKD");
    int before = source.arrived();
    quote();
    TransactionTemplate transaction =
        new TransactionTemplate(ledger.app.getBean(PlatformTransactionManager.class));
    ApplyPriceUpdate apply = ledger.app.getBean(ApplyPriceUpdate.class);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      apply.apply(update("43.00"));
                      throw new IllegalStateException(
                          "the caller's transaction failed after the update");
                    }))
        .isInstanceOf(IllegalStateException.class);

    quote();
    assertThat(source.arrived() - before).isEqualTo(1);
  }
}
