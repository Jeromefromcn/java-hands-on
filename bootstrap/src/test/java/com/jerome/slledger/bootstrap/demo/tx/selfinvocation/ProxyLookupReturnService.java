package com.jerome.slledger.bootstrap.demo.tx.selfinvocation;

import org.springframework.aop.framework.AopContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fix 2: look up the proxy currently handling this call and call through it. Needs {@code
 * exposeProxy = true}; without it, {@code currentProxy()} throws {@code IllegalStateException}.
 */
public class ProxyLookupReturnService {

  private final JdbcClient jdbc;
  private volatile boolean transactionActiveInRecordReturn;

  public ProxyLookupReturnService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void returnAll() {
    ((ProxyLookupReturnService) AopContext.currentProxy()).recordReturn();
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
