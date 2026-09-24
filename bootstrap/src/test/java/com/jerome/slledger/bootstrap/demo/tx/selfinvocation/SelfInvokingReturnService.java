package com.jerome.slledger.bootstrap.demo.tx.selfinvocation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The broken shape: the transactional method is called on {@code this}, not on the proxy. */
public class SelfInvokingReturnService {

  private final JdbcClient jdbc;
  private volatile boolean transactionActiveInRecordReturn;

  public SelfInvokingReturnService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void returnAll() {
    this.recordReturn();
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
