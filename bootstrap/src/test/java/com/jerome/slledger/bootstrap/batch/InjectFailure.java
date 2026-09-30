package com.jerome.slledger.bootstrap.batch;

import com.jerome.slledger.batch.support.FailurePoint;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A test-only configuration that fails the accrual writer on chosen calls with a chosen exception.
 * A call is one attempt at writing a chunk, so a retried chunk is several calls. Unlike {@link
 * FailAt}, which fails once, this can fail the same chunk repeatedly.
 */
class InjectFailure {

  static final AtomicInteger CALLS = new AtomicInteger();
  private static volatile IntPredicate failOn = call -> false;
  private static volatile Supplier<RuntimeException> failure = IllegalStateException::new;

  static void reset() {
    CALLS.set(0);
    failOn = call -> false;
  }

  /** Fails every call for which {@code onCall} holds (1-based) with a fresh {@code exception}. */
  static void on(IntPredicate onCall, Supplier<RuntimeException> exception) {
    CALLS.set(0);
    failOn = onCall;
    failure = exception;
  }

  @Bean
  @Primary
  FailurePoint injected() {
    return (step, firstKey) -> {
      if (failOn.test(CALLS.incrementAndGet())) {
        throw failure.get();
      }
    };
  }
}
