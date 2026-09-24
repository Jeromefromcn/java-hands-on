package com.jerome.slledger.bootstrap.demo.tx.idempotency;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** A return that must happen once per request key: decrement, book the trade, maybe fail. */
public class IdempotentReturn {

  private final JdbcClient jdbc;
  private final TxDemoRows rows;
  private volatile CountDownLatch hold;

  public IdempotentReturn(JdbcClient jdbc, TxDemoRows rows) {
    this.jdbc = jdbc;
    this.rows = rows;
  }

  @Idempotent
  @Transactional
  public void returnQuantity(String key, String positionId, long quantity, boolean fail) {
    jdbc.sql(
            "UPDATE loan_position SET outstanding_quantity = outstanding_quantity - ?"
                + " WHERE id = ?")
        .params(quantity, positionId)
        .update();
    rows.insertTrade(positionId, quantity);
    awaitHold();
    if (fail) {
      throw new IllegalStateException("return failed after booking");
    }
  }

  /**
   * Test hook: whoever gets this far waits on the latch, still inside its business work, so a
   * racing duplicate can be observed while the winner's transaction is open. Null means no hold.
   */
  public void holdInsideBusinessWork(CountDownLatch latch) {
    this.hold = latch;
  }

  private void awaitHold() {
    CountDownLatch latch = hold;
    if (latch == null) {
      return;
    }
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("never released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
