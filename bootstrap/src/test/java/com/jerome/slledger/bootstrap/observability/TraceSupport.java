package com.jerome.slledger.bootstrap.observability;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.context.annotation.Bean;

/**
 * Where the tracing tests read spans from: the real OpenTelemetry SDK with a processor that keeps
 * each span in memory as it ends. The claim under test is what the real propagator and the real
 * bridge do, so nothing here fakes either.
 */
public final class TraceSupport {

  public static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

  private TraceSupport() {}

  /** Test-only source, registered by class: not a component of the application. */
  public static class InMemorySpans {
    @Bean
    SpanProcessor inMemorySpanProcessor() {
      return SimpleSpanProcessor.create(SPANS);
    }
  }
}
