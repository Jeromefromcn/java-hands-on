package com.jerome.slledger.adapter.web.layers;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.web.error.LedgerProblemHandler;
import com.jerome.slledger.adapter.web.error.TraceIdOnProblems;
import com.jerome.slledger.adapter.web.json.LedgerJacksonModule;
import com.jerome.slledger.adapter.web.testing.Evidence;
import com.jerome.slledger.adapter.web.testing.ProbeController;
import com.jerome.slledger.adapter.web.trace.TraceIdFilter;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The order in which a filter, an interceptor and an aspect see one request, on a real Tomcat:
 * MockMvc buffers the response, and whether it is committed by the time a layer runs is part of
 * what each layer can still change. See docs/decisions/07-web.md.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = LayerOrderTest.ProbeApplication.class)
class LayerOrderTest {

  /** The probe controller and the production web pieces around it; no use cases, no scan. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({
    ProbeController.class,
    TraceIdFilter.class,
    LedgerProblemHandler.class,
    TraceIdOnProblems.class,
    LedgerJacksonModule.class,
    LayerProbes.class
  })
  static class ProbeApplication {}

  private static final StringBuilder EVIDENCE =
      new StringBuilder(
          "Stage 07 · section 13 — filter, interceptor, aspect on one request (LayerOrderTest)\n"
              + "Real Tomcat on a random port. TraceIdFilter runs first; the recording filter\n"
              + "runs right after it.\n\n");

  @LocalServerPort int port;

  private RestTestClient http;

  @BeforeEach
  void client() {
    http = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @AfterEach
  void reset() {
    ProbeController.reset();
  }

  @AfterAll
  static void write() {
    Evidence.write("07-layer-order.txt", EVIDENCE.toString());
  }

  @Test
  @DisplayName("success: filter, interceptor, aspect in; out in reverse; the body is written first")
  void success() {
    List<String> log = run("A handler that returns", c -> c.get().uri("/probe").exchange());

    assertThat(log)
        .extracting(LayerOrderTest::layerAndPhase)
        .containsExactly(
            "filter >",
            "interceptor >",
            "aspect >",
            "aspect <",
            "interceptor postHandle",
            "interceptor <",
            "filter <");
    // A @ResponseBody is written before postHandle: by then a header can no longer be added.
    assertThat(log.get(4)).endsWith("committed true");
  }

  @Test
  @DisplayName("a throwing handler: only the aspect sees the exception; postHandle is skipped")
  void handlerThrows() {
    ProbeController.throwing(new IllegalStateException("boom"));

    List<String> log = run("A handler that throws", c -> c.get().uri("/probe").exchange());

    assertThat(log)
        .extracting(LayerOrderTest::layerAndPhase)
        .containsExactly(
            "filter >", "interceptor >", "aspect >", "aspect !", "interceptor <", "filter <");
    assertThat(log.get(3)).contains("IllegalStateException: boom");
    // The advice resolved it first, so neither the interceptor nor the filter sees an exception.
    assertThat(log.get(4)).contains("exception null, status 500");
    assertThat(log.get(5)).contains("status 500");
  }

  @Test
  @DisplayName("refused before the handler is chosen: only the filter sees it")
  void refusedBeforeHandler() {
    List<String> wrongMethod =
        run("Wrong method (405)", c -> c.post().uri("/probe/header").exchange());
    List<String> wrongType =
        run(
            "Wrong content type (415)",
            c -> c.post().uri("/probe").contentType(MediaType.TEXT_PLAIN).body("x").exchange());

    assertThat(wrongMethod)
        .extracting(LayerOrderTest::layerAndPhase)
        .containsExactly("filter >", "filter <");
    assertThat(wrongType)
        .extracting(LayerOrderTest::layerAndPhase)
        .containsExactly("filter >", "filter <");
  }

  @Test
  @DisplayName("an unknown path has a handler after all, the static resource handler")
  void unknownPath() {
    List<String> log = run("Unknown path (404)", c -> c.get().uri("/no-such-path").exchange());

    assertThat(log.get(1)).contains("handler ResourceHttpRequestHandler");
    assertThat(log).noneMatch(line -> line.startsWith("aspect"));
  }

  @Test
  @DisplayName("an invalid body is refused after the handler is chosen but before the aspect runs")
  void invalidBody() {
    List<String> log =
        run(
            "Invalid body (400)",
            c ->
                c.post()
                    .uri("/probe")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"value\":\"\"}")
                    .exchange());

    assertThat(log)
        .extracting(LayerOrderTest::layerAndPhase)
        .containsExactly("filter >", "interceptor >", "interceptor <", "filter <");
  }

  private List<String> run(
      String title, Function<RestTestClient, RestTestClient.ResponseSpec> request) {
    LayerProbes.LOG.clear();
    request.apply(http);
    List<String> log = List.copyOf(LayerProbes.LOG);
    EVIDENCE.append(title).append(":\n");
    log.forEach(line -> EVIDENCE.append("  ").append(line).append('\n'));
    EVIDENCE.append('\n');
    return log;
  }

  private static String layerAndPhase(String line) {
    String[] parts = line.trim().split("\\s+");
    return parts[0] + " " + parts[1].replace(",", "");
  }
}
