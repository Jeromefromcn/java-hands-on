package com.jerome.slledger.bootstrap.demo.tx.async;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Books a trade and defers the async read until the transaction has committed. Not crash-safe: a
 * crash between the commit and the callback loses the read. That gap is the Outbox's job.
 */
public class BookingWithAfterCommitRead {

  private final TxDemoRows rows;
  private final TradeReader reader;
  private final AtomicInteger afterCommitCalls = new AtomicInteger();
  private volatile CompletableFuture<Boolean> lastResult;

  public BookingWithAfterCommitRead(TxDemoRows rows, TradeReader reader) {
    this.rows = rows;
    this.reader = reader;
  }

  /** The returned future completes with what the read saw, once the transaction commits. */
  @Transactional
  public CompletableFuture<Boolean> book(String positionId, boolean failAfterInsert) {
    long tradeId = rows.insertTrade(positionId, 10);
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    lastResult = result;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            afterCommitCalls.incrementAndGet();
            reader
                .findAsync(tradeId)
                .whenComplete(
                    (seen, failure) -> {
                      if (failure != null) {
                        result.completeExceptionally(failure);
                      } else {
                        result.complete(seen);
                      }
                    });
          }
        });
    if (failAfterInsert) {
      throw new IllegalStateException("booking failed after the insert");
    }
    return result;
  }

  public int afterCommitCalls() {
    return afterCommitCalls.get();
  }

  /** The future the last call created, reachable even when that call threw. */
  public CompletableFuture<Boolean> lastResult() {
    return lastResult;
  }
}
