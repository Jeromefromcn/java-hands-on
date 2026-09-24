package com.jerome.slledger.bootstrap.demo.tx.async;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;

/** Reads a trade on a pool thread: no transaction of the caller's, and a connection of its own. */
public class TradeReader {

  private final TxDemoRows rows;
  private volatile String lastThreadName;

  public TradeReader(TxDemoRows rows) {
    this.rows = rows;
  }

  @Async
  public CompletableFuture<Boolean> findAsync(long tradeId) {
    lastThreadName = Thread.currentThread().getName();
    return CompletableFuture.completedFuture(rows.tradeExists(tradeId));
  }

  public String lastThreadName() {
    return lastThreadName;
  }
}
