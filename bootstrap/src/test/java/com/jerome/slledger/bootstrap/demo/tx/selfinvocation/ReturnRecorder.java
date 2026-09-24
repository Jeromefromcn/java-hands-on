package com.jerome.slledger.bootstrap.demo.tx.selfinvocation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fix 1's second bean: the only reason it exists is to be reached through its proxy. */
public class ReturnRecorder {

  private final JdbcClient jdbc;
  private volatile boolean transactionActiveInRecordReturn;

  public ReturnRecorder(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void recordReturn() {
    transactionActiveInRecordReturn = TransactionSynchronizationManager.isActualTransactionActive();
    ReturnOfThirty.decrementThenFailBooking(jdbc);
  }

  public boolean transactionActiveInRecordReturn() {
    return transactionActiveInRecordReturn;
  }
}
