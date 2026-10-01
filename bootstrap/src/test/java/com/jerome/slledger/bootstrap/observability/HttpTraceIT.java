package com.jerome.slledger.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jerome.slledger.bootstrap.position.LedgerApp;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** The trace id of a request is one value: on the span, in the response, and in the logs' MDC. */
@Timeout(120)
class HttpTraceIT {

  private static final InMemorySpanExporter SPANS = TraceSupport.SPANS;

  private static ConfigurableApplicationContext app;
  private static RestTestClient http;

  @BeforeAll
  static void start() {
    app = LedgerApp.startWeb(List.of(TraceSupport.InMemorySpans.class));
    http = RestTestClient.bindToServer().baseUrl("http://localhost:" + LedgerApp.port(app)).build();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  @BeforeEach
  void clear() {
    SPANS.reset();
  }

  private static SpanData serverSpan() {
    await().atMost(Duration.ofSeconds(10)).until(() -> !SPANS.getFinishedSpanItems().isEmpty());
    return SPANS.getFinishedSpanItems().getFirst();
  }

  @Test
  @DisplayName("X-Trace-Id on the response is the trace id of the request's span")
  void responseHeaderIsTheSpansTraceId() {
    EntityExchangeResult<String> result =
        http.get().uri("/loans").exchange().returnResult(String.class);

    String header = result.getResponseHeaders().getFirst("X-Trace-Id");
    assertThat(header).matches("[0-9a-f]{32}");
    assertThat(serverSpan().getTraceId()).isEqualTo(header);
  }

  @Test
  @DisplayName("an incoming traceparent is continued; an incoming X-Trace-Id is still ignored")
  void incomingTraceparentIsContinued() {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    EntityExchangeResult<String> result =
        http.get()
            .uri("/loans")
            .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
            .header("X-Trace-Id", "attacker-chosen")
            .exchange()
            .returnResult(String.class);

    assertThat(result.getResponseHeaders().getFirst("X-Trace-Id")).isEqualTo(traceId);
    SpanData span = serverSpan();
    assertThat(span.getTraceId()).isEqualTo(traceId);
    assertThat(span.getParentSpanId()).isEqualTo("00f067aa0ba902b7");
  }

  @Test
  @DisplayName("a request with a malformed traceparent still gets a trace of its own")
  void malformedTraceparentStartsANewTrace() {
    EntityExchangeResult<String> result =
        http.get()
            .uri("/loans")
            .header("traceparent", "not-a-traceparent")
            .exchange()
            .returnResult(String.class);

    assertThat(result.getResponseHeaders().getFirst("X-Trace-Id")).matches("[0-9a-f]{32}");
  }
}
