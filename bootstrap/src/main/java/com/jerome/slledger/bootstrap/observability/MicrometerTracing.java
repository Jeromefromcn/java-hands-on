package com.jerome.slledger.bootstrap.observability;

import com.jerome.slledger.application.observability.Tracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link Tracing} on Micrometer Tracing. The propagator is the W3C one, so what is stored in the
 * outbox and put on the Kafka record is the same header any other W3C-aware system would send.
 */
public final class MicrometerTracing implements Tracing {

  static final String TRACEPARENT = "traceparent";

  private final Tracer tracer;
  private final Propagator propagator;

  public MicrometerTracing(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  @Override
  public Optional<String> currentTraceId() {
    Span span = tracer.currentSpan();
    return span == null ? Optional.empty() : Optional.ofNullable(span.context().traceId());
  }

  @Override
  public Optional<String> currentTraceparent() {
    Span span = tracer.currentSpan();
    if (span == null) {
      return Optional.empty();
    }
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(span.context(), carrier, Map::put);
    return Optional.ofNullable(carrier.get(TRACEPARENT));
  }

  @Override
  @SuppressWarnings("try") // the scope is held for its effect: the span is current inside the block
  public <T> T inSpan(String name, String traceparent, Supplier<T> work) {
    Span.Builder builder =
        traceparent == null || traceparent.isBlank()
            ? tracer.spanBuilder()
            : propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get);
    Span span = builder.name(name).kind(Span.Kind.CONSUMER).start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      return work.get();
    } catch (RuntimeException failure) {
      span.error(failure);
      throw failure;
    } finally {
      span.end();
    }
  }
}
