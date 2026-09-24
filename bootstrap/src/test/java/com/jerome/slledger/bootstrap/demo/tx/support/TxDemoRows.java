package com.jerome.slledger.bootstrap.demo.tx.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * The row-level reads and writes every stage-5 demo shares. Each call runs in whatever transaction
 * is active on the calling thread, or in autocommit if there is none.
 */
public class TxDemoRows {

  private final JdbcClient jdbc;

  public TxDemoRows(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insertPosition(String id, long quantity) {
    jdbc.sql("INSERT INTO loan_position(id, outstanding_quantity) VALUES (?, ?)")
        .params(id, quantity)
        .update();
  }

  public long outstanding(String id) {
    return jdbc.sql("SELECT outstanding_quantity FROM loan_position WHERE id = ?")
        .param(id)
        .query(Long.class)
        .single();
  }

  public long version(String id) {
    return jdbc.sql("SELECT version FROM loan_position WHERE id = ?")
        .param(id)
        .query(Long.class)
        .single();
  }

  public long tradeCount(String positionId) {
    return jdbc.sql("SELECT count(*) FROM loan_trade WHERE position_id = ?")
        .param(positionId)
        .query(Long.class)
        .single();
  }

  public long insertTrade(String positionId, long quantity) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("INSERT INTO loan_trade(position_id, quantity) VALUES (?, ?)")
        .params(positionId, quantity)
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }

  public boolean tradeExists(long id) {
    return jdbc.sql("SELECT count(*) FROM loan_trade WHERE id = ?")
            .param(id)
            .query(Long.class)
            .single()
        > 0;
  }

  public long accrualRunCount() {
    return jdbc.sql("SELECT count(*) FROM accrual_run").query(Long.class).single();
  }
}
