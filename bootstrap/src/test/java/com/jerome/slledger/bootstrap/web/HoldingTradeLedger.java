package com.jerome.slledger.bootstrap.web;

import com.jerome.slledger.application.port.BookedTrade;
import com.jerome.slledger.application.port.RequestFingerprint;
import com.jerome.slledger.application.port.TradeLedger;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanTrade;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only. Wraps the real ledger and, while armed, holds the <strong>first</strong> caller right
 * after its trade row is inserted, inside its still-open transaction, until the test releases it.
 * That is the window in which a second request with the same key arrives. No production class has a
 * hook for it.
 */
public class HoldingTradeLedger implements TradeLedger {

  private final TradeLedger delegate;
  private final AtomicBoolean armed = new AtomicBoolean();
  private volatile CountDownLatch booked = new CountDownLatch(1);
  private volatile CountDownLatch release = new CountDownLatch(1);
  private volatile boolean failAfterRelease;

  public HoldingTradeLedger(TradeLedger delegate) {
    this.delegate = delegate;
  }

  /** Hold the next booking; on release, let it commit or make its transaction roll back. */
  public void arm(boolean rollBackOnRelease) {
    booked = new CountDownLatch(1);
    release = new CountDownLatch(1);
    failAfterRelease = rollBackOnRelease;
    armed.set(true);
  }

  public boolean awaitHeld() throws InterruptedException {
    return booked.await(10, TimeUnit.SECONDS);
  }

  public void release() {
    release.countDown();
  }

  @Override
  public void book(LoanTrade trade, RequestFingerprint fingerprint) {
    delegate.book(trade, fingerprint);
    if (armed.compareAndSet(true, false)) {
      booked.countDown();
      try {
        if (!release.await(20, TimeUnit.SECONDS)) {
          throw new IllegalStateException("The test never released the held booking");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      if (failAfterRelease) {
        throw new IllegalStateException("held booking told to roll back");
      }
    }
  }

  @Override
  public Optional<BookedTrade> findByKey(IdempotencyKey key) {
    return delegate.findByKey(key);
  }
}
