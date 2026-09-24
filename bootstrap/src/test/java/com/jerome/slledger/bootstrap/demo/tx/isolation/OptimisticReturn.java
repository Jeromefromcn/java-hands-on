package com.jerome.slledger.bootstrap.demo.tx.isolation;

import java.util.concurrent.CyclicBarrier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** One attempt: write only if the version read is still the version stored. */
public class OptimisticReturn {

  private final JdbcClient jdbc;

  public OptimisticReturn(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns whether the write landed; {@code false} means someone else wrote first. */
  @Transactional
  public boolean tryReturn(String id, long quantity, CyclicBarrier afterRead) {
    record Read(long outstanding, long version) {}
    Read read =
        jdbc.sql("SELECT outstanding_quantity, version FROM loan_position WHERE id = ?")
            .param(id)
            .query((rs, rowNum) -> new Read(rs.getLong(1), rs.getLong(2)))
            .single();
    AfterRead.await(afterRead);
    int updated =
        jdbc.sql(
                "UPDATE loan_position SET outstanding_quantity = ?, version = version + 1"
                    + " WHERE id = ? AND version = ?")
            .params(read.outstanding() - quantity, id, read.version())
            .update();
    return updated == 1;
  }
}
