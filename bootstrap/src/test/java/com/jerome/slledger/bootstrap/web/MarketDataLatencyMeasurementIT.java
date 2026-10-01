package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.domain.instrument.Isin;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The latency of a healthy source, through the protected client, on this machine. The slow-call
 * threshold and the read timeout in {@code docs/decisions/12-resilience-observability.md} are read
 * against these numbers: a threshold below the healthy tail would open the breaker on a healthy
 * source; one above the read timeout would never fire.
 *
 * <p>The stub is local, so these are the client's own costs (connection, JSON, the breaker), a
 * floor and not a model of a real source.
 */
@Tag("measurement")
@Timeout(300)
class MarketDataLatencyMeasurementIT {

  private static final int WARM_UP = 200;
  private static final int CALLS = 2_000;

  @Test
  void healthySourceLatency() {
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger = WebLedger.start("--ledger.marketdata.base-url=" + source.baseUrl())) {
      source.price(HK_EQUITY, "41.30", "HKD");
      MarketPriceSource client = ledger.app.getBean(MarketPriceSource.class);
      Isin isin = new Isin(HK_EQUITY);
      for (int i = 0; i < WARM_UP; i++) {
        client.priceOf(isin);
      }

      long[] micros = new long[CALLS];
      for (int i = 0; i < CALLS; i++) {
        long start = System.nanoTime();
        client.priceOf(isin);
        micros[i] = (System.nanoTime() - start) / 1_000;
      }
      Arrays.sort(micros);

      String report =
          """
          healthy market data source, stub on loopback, %d calls after %d warm-up (microseconds)
          p50  %d
          p90  %d
          p99  %d
          p999 %d
          max  %d
          """
              .formatted(
                  CALLS,
                  WARM_UP,
                  micros[CALLS / 2],
                  micros[CALLS * 90 / 100],
                  micros[CALLS * 99 / 100],
                  micros[CALLS * 999 / 1000],
                  micros[CALLS - 1]);
      System.out.println(report);
      Evidence.write("12-market-data-latency.txt", report);
      assertThat(micros[CALLS * 99 / 100]).isLessThan(1_000_000L);
    }
  }
}
