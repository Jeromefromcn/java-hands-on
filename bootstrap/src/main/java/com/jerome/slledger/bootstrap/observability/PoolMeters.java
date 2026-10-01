package com.jerome.slledger.bootstrap.observability;

import com.jerome.slledger.bootstrap.config.MarketDataPool;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/** The market data executor: how busy it is, how much waits, and how much it has turned away. */
public final class PoolMeters implements MeterBinder {

  private final MarketDataPool pool;

  public PoolMeters(MarketDataPool pool) {
    this.pool = pool;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ledger.marketdata.pool.active", pool, p -> p.stats().active())
        .register(registry);
    Gauge.builder("ledger.marketdata.pool.queued", pool, p -> p.stats().queued())
        .register(registry);
    Gauge.builder("ledger.marketdata.pool.size", pool, p -> p.stats().poolSize())
        .register(registry);
    FunctionCounter.builder("ledger.marketdata.pool.rejected", pool, p -> p.stats().rejected())
        .description("Tasks the executor refused")
        .register(registry);
    FunctionCounter.builder("ledger.marketdata.pool.completed", pool, p -> p.stats().completed())
        .register(registry);
  }
}
