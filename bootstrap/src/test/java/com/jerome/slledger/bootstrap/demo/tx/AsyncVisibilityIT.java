package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.async.AsyncConfig;
import com.jerome.slledger.bootstrap.demo.tx.async.BookingWithAsyncRead;
import com.jerome.slledger.bootstrap.demo.tx.async.TradeReader;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A booking inserts a trade, then waits, still inside its transaction, for an {@code @Async} read
 * of that trade. The read runs on another thread, so on another connection.
 */
@Timeout(30)
class AsyncVisibilityIT {

  // The transaction is bound to the calling thread; the @Async method runs on a pool thread, takes
  // its own connection, and cannot see a row the caller has not committed yet.
  // See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName("an @Async read inside the transaction does not see the uncommitted trade")
  void reproducesAsyncReadBeforeCommit() {
    try (var context =
        TxDemo.start(AsyncConfig.class, TradeReader.class, BookingWithAsyncRead.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      BookingWithAsyncRead booking = context.getBean(BookingWithAsyncRead.class);

      boolean seenByAsyncRead = booking.book("P1");

      assertThat(seenByAsyncRead).isFalse();
      // The row was there all along, just not committed when the other connection looked.
      assertThat(rows.tradeExists(booking.lastTradeId())).isTrue();
    }
  }

  @Test
  @DisplayName("the async read really ran on another thread, or the first test proves nothing")
  void asyncReadRunsOnAnotherThread() {
    try (var context =
        TxDemo.start(AsyncConfig.class, TradeReader.class, BookingWithAsyncRead.class)) {
      BookingWithAsyncRead booking = context.getBean(BookingWithAsyncRead.class);

      booking.book("P1");

      assertThat(context.getBean(TradeReader.class).lastThreadName())
          .isNotNull()
          .isNotEqualTo(Thread.currentThread().getName());
    }
  }
}
