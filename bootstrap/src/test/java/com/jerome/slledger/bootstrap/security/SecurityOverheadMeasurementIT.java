package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * What a token costs per request on this machine: verifying one (RS256, 2048-bit key, no cache), a
 * request refused for having none, and the same request with one. The numbers are read in {@code
 * docs/decisions/13-security.md} against what a 100x load would make of them.
 */
@Tag("measurement")
@Timeout(600)
class SecurityOverheadMeasurementIT {

  private static final int WARM_UP = 2_000;
  private static final int CALLS = 5_000;

  private static String summary(String label, long[] sortedMicros) {
    long sum = Arrays.stream(sortedMicros).sum();
    return "%-44s mean %6.1f  p50 %5d  p99 %5d  max %6d%n"
        .formatted(
            label,
            sum / (double) sortedMicros.length,
            sortedMicros[sortedMicros.length / 2],
            sortedMicros[(int) (sortedMicros.length * 0.99)],
            sortedMicros[sortedMicros.length - 1]);
  }

  private static long[] http(HttpClient client, HttpRequest request, int expected)
      throws Exception {
    for (int i = 0; i < WARM_UP; i++) {
      client.send(request, HttpResponse.BodyHandlers.discarding());
    }
    long[] micros = new long[CALLS];
    for (int i = 0; i < CALLS; i++) {
      long start = System.nanoTime();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      micros[i] = (System.nanoTime() - start) / 1_000;
      assertThat(response.statusCode()).isEqualTo(expected);
    }
    Arrays.sort(micros);
    return micros;
  }

  @Test
  void whatATokenCosts() throws Exception {
    try (ConfigurableApplicationContext app = LedgerApp.startWeb()) {
      JwtDecoder decoder = app.getBean(JwtDecoder.class);
      String token = TestTokens.trader();
      for (int i = 0; i < WARM_UP * 5; i++) {
        decoder.decode(token);
      }
      long[] decode = new long[CALLS * 4];
      for (int i = 0; i < decode.length; i++) {
        long start = System.nanoTime();
        decoder.decode(token);
        decode[i] = (System.nanoTime() - start) / 1_000;
      }
      Arrays.sort(decode);

      HttpClient client = HttpClient.newHttpClient();
      URI loans = URI.create("http://localhost:" + LedgerApp.port(app) + "/loans");
      long[] refused = http(client, HttpRequest.newBuilder(loans).build(), 401);
      long[] served =
          http(
              client,
              HttpRequest.newBuilder(loans)
                  .header("Authorization", "Bearer " + TestTokens.trader())
                  .build(),
              200);

      String report =
          "Stage 13 · what a token costs per request (SecurityOverheadMeasurementIT)\n"
              + "Microseconds, this machine, JDK 21, one client, sequential, %d calls after %d warm-up.%n%n"
                  .formatted(CALLS, WARM_UP)
              + summary("JwtDecoder.decode (RS256 2048, in-process)", decode)
              + summary("GET /loans, no token -> 401", refused)
              + summary("GET /loans, valid token -> 200", served);
      Evidence.write("13-security-overhead.txt", report);
    }
  }
}
