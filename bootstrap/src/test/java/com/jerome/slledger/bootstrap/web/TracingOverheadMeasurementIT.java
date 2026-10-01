package com.jerome.slledger.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What tracing costs a request on this machine: {@code GET /loans} (a database read, no external
 * call) timed at sampling 0, 0.1 and 1.0, with spans created and not exported. The prod sampling
 * rate in {@code docs/decisions/12-resilience-observability.md} is read against these numbers.
 */
@Tag("measurement")
@Timeout(900)
class TracingOverheadMeasurementIT {

  private static final int WARM_UP = 1_000;
  private static final int CALLS = 5_000;

  private static long[] timings(double sampling) throws Exception {
    try (ConfigurableApplicationContext app =
        LedgerApp.startWeb("--management.tracing.sampling.probability=" + sampling)) {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + LedgerApp.port(app) + "/loans"))
              .build();
      for (int i = 0; i < WARM_UP; i++) {
        client.send(request, HttpResponse.BodyHandlers.discarding());
      }
      long[] micros = new long[CALLS];
      for (int i = 0; i < CALLS; i++) {
        long start = System.nanoTime();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        micros[i] = (System.nanoTime() - start) / 1_000;
        assertThat(response.statusCode()).isEqualTo(200);
      }
      Arrays.sort(micros);
      return micros;
    }
  }

  @Test
  void samplingCost() throws Exception {
    StringBuilder report =
        new StringBuilder(
            "GET /loans, %d calls after %d warm-up, spans created and not exported (microseconds)\n"
                .formatted(CALLS, WARM_UP));
    // The JVM gets faster as it runs, so a single pass in a fixed order measures the order. The
    // settings run forwards and then backwards: a setting's two runs sit at mirrored positions, and
    // a steady drift cancels in their average.
    int run = 0;
    for (double sampling : new double[] {0.0, 0.1, 1.0, 1.0, 0.1, 0.0}) {
      long[] t = timings(sampling);
      report.append(
          "run %d  sampling %.1f: p50 %d  p90 %d  p99 %d  max %d\n"
              .formatted(
                  ++run,
                  sampling,
                  t[CALLS / 2],
                  t[CALLS * 90 / 100],
                  t[CALLS * 99 / 100],
                  t[CALLS - 1]));
    }
    System.out.println(report);
    Evidence.write("12-tracing-overhead.txt", report.toString());
  }
}
