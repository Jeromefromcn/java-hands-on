package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.messaging.ApplyPriceUpdate;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The timers do the work without being called: a deferred revaluation is dispatched by itself. */
@Timeout(300)
class BatchSchedulersIT {

  @Test
  void theDispatcherLaunchesADeferredRevaluationOnItsOwn() throws Exception {
    try (BatchLedger ledger =
        BatchLedger.startWithOutbox(
            "--ledger.batch.schedulers-enabled=true",
            "--ledger.batch.dispatch-interval=200ms",
            "--ledger.batch.chunk-size=20",
            "--ledger.revaluation.inline-limit=5")) {
      ledger.seed(30);
      ledger
          .bean(ApplyPriceUpdate.class)
          .apply(
              new PriceUpdated(
                  UUID.randomUUID(),
                  Instant.now(),
                  new Isin("HK0000000001"),
                  Money.of("41.26", Currency.getInstance("HKD")),
                  3));

      // The request is marked DONE after the job ends, in a transaction of its own: the outbox can
      // hold all 30 rows a moment before it does (a CI runner caught that moment).
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (ledger.count("outbox_event") < 30
          || !"DONE"
              .equals(
                  ledger
                      .jdbc
                      .sql("SELECT status FROM revaluation_request")
                      .query(String.class)
                      .single())) {
        assertThat(System.nanoTime()).as("the dispatcher never ran the job").isLessThan(deadline);
        Thread.sleep(100);
      }

      assertThat(ledger.count("outbox_event")).isEqualTo(30);
      assertThat(
              ledger
                  .jdbc
                  .sql("SELECT status FROM revaluation_request")
                  .query(String.class)
                  .single())
          .isEqualTo("DONE");
    }
  }
}
