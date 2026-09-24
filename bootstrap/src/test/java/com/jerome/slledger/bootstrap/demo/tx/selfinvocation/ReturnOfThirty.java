package com.jerome.slledger.bootstrap.demo.tx.selfinvocation;

import org.springframework.jdbc.core.simple.JdbcClient;

/** The unit of work every variant runs: decrement P1 by 30, then fail before booking the trade. */
final class ReturnOfThirty {

  private ReturnOfThirty() {}

  static void decrementThenFailBooking(JdbcClient jdbc) {
    jdbc.sql(
            "UPDATE loan_position SET outstanding_quantity = outstanding_quantity - 30"
                + " WHERE id = 'P1'")
        .update();
    // The INSERT INTO loan_trade would come next. It never runs: the booking fails first, so the
    // decrement above must roll back with it, or the position and its trades disagree.
    throw new IllegalStateException("trade booking failed");
  }
}
