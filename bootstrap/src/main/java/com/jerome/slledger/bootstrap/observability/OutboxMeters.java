package com.jerome.slledger.bootstrap.observability;

import com.jerome.slledger.application.messaging.OutboxStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;

/**
 * How far behind the outbox relay is, as outcomes: the age of the oldest message nobody has sent
 * yet, and how many are waiting. The age is what an alert reads. Zero means nothing is waiting.
 */
public final class OutboxMeters implements MeterBinder {

  private final OutboxStore store;
  private final Clock clock;
  private final CachedValue.Clocked cache = new CachedValue.Clocked(2_000);

  public OutboxMeters(OutboxStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(
            "ledger.outbox.oldest.age.seconds",
            () ->
                cache.get(
                    "age",
                    () ->
                        store
                            .oldestUnpublishedSince()
                            .map(
                                since ->
                                    (double) Duration.between(since, clock.instant()).toSeconds())
                            .orElse(0.0)))
        .description("Age of the oldest outbox message not yet sent; 0 when none is waiting")
        .register(registry);
    Gauge.builder(
            "ledger.outbox.unpublished", () -> cache.get("count", () -> store.countUnpublished()))
        .description("Outbox messages waiting to be sent")
        .register(registry);
  }
}
