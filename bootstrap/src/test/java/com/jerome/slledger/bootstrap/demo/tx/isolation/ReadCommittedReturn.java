package com.jerome.slledger.bootstrap.demo.tx.isolation;

import java.util.concurrent.CyclicBarrier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class ReadCommittedReturn implements ReadModifyWriteReturn {

  private final JdbcClient jdbc;

  public ReadCommittedReturn(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void returnQuantity(String id, long quantity, CyclicBarrier afterRead) {
    ReadModifyWriteReturn.readModifyWrite(jdbc, id, quantity, afterRead);
  }
}
