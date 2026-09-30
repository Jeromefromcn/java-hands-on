package com.jerome.slledger.application.context;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the use cases may know about the request being served, held per thread.
 *
 * <p>A {@link ThreadLocal} belongs to a thread, not to a request. Threads are pooled, so a value
 * left behind is read by whatever request or task the thread serves next, and a value set on the
 * submitting thread is invisible to the executor's threads. Both are wrong the same way: the
 * context follows the thread instead of the work.
 *
 * <p>The rule here is that the work carries it. {@link #propagating} captures the submitter's
 * context, sets it for the task, and puts back whatever the executing thread had before, in {@code
 * finally}. It restores rather than clears: under a caller-runs policy the task executes on the
 * submitter's own thread, and clearing would erase the context the request is still using.
 */
public final class RequestContext {

  /** The per-request value. Only the trace id today; the type is here to grow without churn. */
  public record Snapshot(String traceId) {}

  private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

  private RequestContext() {}

  public static void set(Snapshot snapshot) {
    CURRENT.set(snapshot);
  }

  public static Optional<Snapshot> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static void clear() {
    CURRENT.remove();
  }

  /** The task, carrying the context of the thread that calls this. */
  public static <T> Supplier<T> propagating(Supplier<T> task) {
    Snapshot captured = CURRENT.get();
    return () -> {
      Snapshot before = CURRENT.get();
      restore(captured);
      try {
        return task.get();
      } finally {
        restore(before);
      }
    };
  }

  private static void restore(Snapshot snapshot) {
    if (snapshot == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(snapshot);
    }
  }
}
