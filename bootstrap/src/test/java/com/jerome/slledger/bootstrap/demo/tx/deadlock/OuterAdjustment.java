package com.jerome.slledger.bootstrap.demo.tx.deadlock;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Takes the position's row lock, then hands the same row to a new transaction. */
public class OuterAdjustment {

  private final JdbcClient jdbc;
  private final InnerAdjustment inner;

  public OuterAdjustment(JdbcClient jdbc, InnerAdjustment inner) {
    this.jdbc = jdbc;
    this.inner = inner;
  }

  @Transactional
  public void adjust(String id) {
    jdbc.sql(
            "UPDATE loan_position SET outstanding_quantity = outstanding_quantity - 1"
                + " WHERE id = ?")
        .param(id)
        .update();
    inner.adjust(id);
  }
}
