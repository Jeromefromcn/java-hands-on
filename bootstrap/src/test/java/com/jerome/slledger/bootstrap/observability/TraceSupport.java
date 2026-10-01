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

  /**
   * The exporter of the context started last. One per context: closing a context shuts its span
   * processor down, and a shut-down in-memory exporter drops every span it is given, so one shared
   * across the test JVM's contexts would silently record nothing for every context after the first.
   */
  private static volatile InMemorySpanExporter current = InMemorySpanExporter.create();

  private TraceSupport() {}

  public static InMemorySpanExporter spans() {
    return current;
  }

  /** Test-only source, registered by class: not a component of the application. */
  public static class InMemorySpans {
    @Bean
    SpanProcessor inMemorySpanProcessor() {
      current = InMemorySpanExporter.create();
      return SimpleSpanProcessor.create(current);
    }
  }
}
