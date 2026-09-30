package com.jerome.slledger.bootstrap.batch;

import com.jerome.slledger.application.accrual.AccrualLedger;
import com.jerome.slledger.application.accrual.BookResult;
import com.jerome.slledger.batch.support.FailurePoint;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-only beans for a multi-instance run, one set per context: a writer that can be slowed or
 * paused at a chosen chunk, and a ledger that counts what it was asked to book. The counters are
 * how a test sees what an instance <em>attempted</em> after it should have stopped.
 */
class InstanceProbe {

  /** What one instance did, and the switches a test turns to pause it. */
  static final class Control {

    final AtomicInteger chunks = new AtomicInteger();
    final AtomicInteger bookCalls = new AtomicInteger();
    final AtomicLong rowsAlreadyPresent = new AtomicLong();
    final AtomicLong rowsInserted = new AtomicLong();
    volatile CountDownLatch reached = new CountDownLatch(1);
    volatile CountDownLatch resume = new CountDownLatch(1);
    volatile int pauseAtChunk = -1;
    volatile long sleepMillisPerChunk;
    volatile boolean paused;

    /** Back to a fresh state between tests: counters zero, no pause, new latches. */
    void reset() {
      chunks.set(0);
      bookCalls.set(0);
      rowsInserted.set(0);
      rowsAlreadyPresent.set(0);
      sleepMillisPerChunk = 0;
      pauseAtChunk = -1;
      paused = false;
      reached = new CountDownLatch(1);
      resume = new CountDownLatch(1);
    }

    void awaitReached() {
      await(reached);
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(60, TimeUnit.SECONDS)) {
          throw new IllegalStateException("latch never opened");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  /** The counting ledger: books through the real one and remembers what came back. */
  static final class CountingLedger implements AccrualLedger {

    private final AccrualLedger real;
    private final Control control;

    CountingLedger(AccrualLedger real, Control control) {
      this.real = real;
      this.control = control;
    }

    @Override
    public BookResult book(List<AccrualEntry> entries) {
      control.bookCalls.incrementAndGet();
      BookResult result = real.book(entries);
      control.rowsInserted.addAndGet(result.inserted());
      control.rowsAlreadyPresent.addAndGet(result.alreadyPresent());
      return result;
    }
  }

  @Bean
  Control control() {
    return new Control();
  }

  @Bean
  @Primary
  AccrualLedger countingLedger(
      @Qualifier("jdbcAccrualLedger") AccrualLedger real, Control control) {
    return new CountingLedger(real, control);
  }

  @Bean
  @Primary
  FailurePoint probe(Control control) {
    return (step, firstKey) -> {
      int chunk = control.chunks.incrementAndGet();
      try {
        if (control.sleepMillisPerChunk > 0) {
          Thread.sleep(control.sleepMillisPerChunk);
        }
        if (chunk == control.pauseAtChunk) {
          control.paused = true;
          control.reached.countDown();
          if (!control.resume.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("never resumed");
          }
          control.paused = false;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    };
  }
}
