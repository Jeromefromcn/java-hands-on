package com.jerome.slledger.bootstrap.batch;

import com.jerome.slledger.batch.support.FailurePoint;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A test-only configuration: the accrual writer fails once, at the chosen chunk, counted across
 * every run in the JVM since {@link #on}. The first {@code n - 1} chunks have committed by then.
 */
class FailAt {

  private static final AtomicInteger CHUNKS = new AtomicInteger();
  private static volatile int failOn = -1;

  static void on(int chunk) {
    CHUNKS.set(0);
    failOn = chunk;
  }

  static void never() {
    failOn = -1;
  }

  @Bean
  @Primary
  FailurePoint failAtChunk() {
    return (step, firstKey) -> {
      int chunk = CHUNKS.incrementAndGet();
      if (chunk == failOn) {
        throw new IllegalStateException(
            "injected failure at chunk %d (first key %s)".formatted(chunk, firstKey));
      }
    };
  }
}
