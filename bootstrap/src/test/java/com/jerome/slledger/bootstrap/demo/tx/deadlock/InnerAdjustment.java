package com.jerome.slledger.bootstrap.demo.tx.deadlock;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs on a second connection, in a second transaction, and updates the row the caller's
 * transaction has locked and will not release until this method returns.
 */
public class InnerAdjustment {

  private final JdbcClient jdbc;

  public InnerAdjustment(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void adjust(String id) {
    // The only thing that ends the wait. SET LOCAL lasts until this transaction ends, so the
    // setting leaves with it rather than staying on the pooled connection.
    jdbc.sql("SET LOCAL lock_timeout = '3s'").update();
    jdbc.sql(
            "UPDATE loan_position SET outstanding_quantity = outstanding_quantity - 1"
                + " WHERE id = ?")
        .param(id)
        .update();
  }
}
