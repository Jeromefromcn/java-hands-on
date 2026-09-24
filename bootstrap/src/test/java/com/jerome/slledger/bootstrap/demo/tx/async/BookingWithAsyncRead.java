package com.jerome.slledger.bootstrap.demo.tx.async;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.transaction.annotation.Transactional;

/** Books a trade and, before committing, asks another thread whether the trade exists. */
public class BookingWithAsyncRead {

  private final TxDemoRows rows;
  private final TradeReader reader;
  private volatile long lastTradeId;

  public BookingWithAsyncRead(TxDemoRows rows, TradeReader reader) {
    this.rows = rows;
    this.reader = reader;
  }

  /** Returns what the async read saw. Waiting here guarantees it looked before the commit. */
  @Transactional
  public boolean book(String positionId) {
    lastTradeId = rows.insertTrade(positionId, 10);
    try {
      return reader.findAsync(lastTradeId).get(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("the async read did not complete", e);
    }
  }

  public long lastTradeId() {
    return lastTradeId;
  }
}
