package com.jerome.slledger.application.observability;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the application needs from distributed tracing, and no more: the identity of the trace the
 * current thread is in, a way to carry it across a boundary the tracing library cannot see, and a
 * way to continue it on the other side.
 *
 * <p>The boundary that needs it is the outbox. An HTTP request's span has ended long before the
 * relay, on its own thread, sends the event the request caused; nothing in either thread links
 * them. The context is therefore written with the event ({@link #currentTraceparent}) and read back
 * when the event is sent and consumed ({@link #inSpan}).
 *
 * <p>The identifier format is W3C Trace Context, {@code traceparent}: {@code 00-<32 hex trace
 * id>-<16 hex span id>-<2 hex flags>}.
 */
public interface Tracing {

  /** No tracer: nothing is current and spans are not created. */
  Tracing NONE =
      new Tracing() {
        @Override
        public Optional<String> currentTraceId() {
          return Optional.empty();
        }

        @Override
        public Optional<String> currentTraceparent() {
          return Optional.empty();
        }

        @Override
        public <T> T inSpan(String name, String traceparent, Supplier<T> work) {
          return work.get();
        }
      };

  /** The id of the trace the current thread is in, if it is in one. */
  Optional<String> currentTraceId();

  /** The context of the current span in the form that travels, if the thread is in one. */
  Optional<String> currentTraceparent();

  /**
   * Runs {@code work} in a new span named {@code name}, a child of the span {@code traceparent}
   * describes. A null, empty or malformed {@code traceparent} starts a new trace instead of
   * failing: a message with a broken header is still a message to be processed.
   */
  <T> T inSpan(String name, String traceparent, Supplier<T> work);

  /** As {@link #inSpan(String, String, Supplier)} for work with no result. */
  default void inSpan(String name, String traceparent, Runnable work) {
    inSpan(
        name,
        traceparent,
        () -> {
          work.run();
          return null;
        });
  }
}
