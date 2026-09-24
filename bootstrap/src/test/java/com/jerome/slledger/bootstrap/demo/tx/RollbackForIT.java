package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.bootstrap.demo.tx.rollback.DefaultRollbackBooking;
import com.jerome.slledger.bootstrap.demo.tx.rollback.RollbackForBooking;
import com.jerome.slledger.bootstrap.demo.tx.rollback.SettlementRejectedException;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A trade is booked, then settlement is rejected with a checked exception. */
class RollbackForIT {

  // By default Spring rolls back only on unchecked exceptions and errors; a checked exception is
  // treated as a business outcome the method declared, and the transaction commits.
  // See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName("a checked exception commits by default: the trade stays booked")
  void reproducesCommitOnCheckedException() {
    try (var context = TxDemo.start(DefaultRollbackBooking.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      DefaultRollbackBooking booking = context.getBean(DefaultRollbackBooking.class);

      assertThatThrownBy(() -> booking.book("P1")).isInstanceOf(SettlementRejectedException.class);

      assertThat(rows.tradeCount("P1")).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("rollbackFor names the checked exception, and the trade rolls back")
  void rollbackForRollsBackCheckedException() {
    try (var context = TxDemo.start(RollbackForBooking.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      RollbackForBooking booking = context.getBean(RollbackForBooking.class);

      assertThatThrownBy(() -> booking.book("P1")).isInstanceOf(SettlementRejectedException.class);

      assertThat(rows.tradeCount("P1")).isZero();
    }
  }
}
