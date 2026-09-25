package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.bootstrap.demo.tx.async.AsyncConfig;
import com.jerome.slledger.bootstrap.demo.tx.async.BookingWithAfterCommitRead;
import com.jerome.slledger.bootstrap.demo.tx.async.TradeReader;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same booking and async read as {@code AsyncVisibilityIT}, but the read is triggered from an
 * {@code afterCommit} callback instead of from inside the transaction.
 */
@Timeout(30)
class AfterCommitIT {

  // Container start-up, including a first image pull, happens here: outside the test timeout.
  @BeforeAll
  static void startPostgres() {
    SharedPostgres.start();
  }

  @Test
  @DisplayName("triggered after commit, the async read sees the trade")
  void afterCommitReadSeesTheTrade() throws Exception {
    try (var context =
        TxDemo.start(AsyncConfig.class, TradeReader.class, BookingWithAfterCommitRead.class)) {
      BookingWithAfterCommitRead booking = context.getBean(BookingWithAfterCommitRead.class);

      CompletableFuture<Boolean> seen = booking.book("P1", false);

      assertThat(seen.get(5, TimeUnit.SECONDS)).isTrue();
      assertThat(booking.afterCommitCalls()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("when the transaction rolls back, the afterCommit callback never fires")
  void rolledBackTransactionNeverPublishes() {
    try (var context =
        TxDemo.start(AsyncConfig.class, TradeReader.class, BookingWithAfterCommitRead.class)) {
      BookingWithAfterCommitRead booking = context.getBean(BookingWithAfterCommitRead.class);

      assertThatThrownBy(() -> booking.book("P1", true)).isInstanceOf(IllegalStateException.class);

      // No timing involved: afterCommit runs synchronously, on this thread, before book() returns.
      // Once book() has thrown, the callback has either run or never will.
      assertThat(booking.afterCommitCalls()).isZero();
      assertThat(booking.lastResult().isDone()).isFalse();
    }
  }
}
