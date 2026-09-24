package com.jerome.slledger.bootstrap.demo.tx.selfinvocation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fix 3: inject the bean into itself. {@code @Lazy} injects a resolving proxy instead of the bean,
 * so there is no construction-time cycle; the first call through it resolves the real (proxied)
 * bean.
 */
public class SelfInjectedReturnService {

  private final JdbcClient jdbc;
  private volatile boolean transactionActiveInRecordReturn;

  @Autowired @Lazy private SelfInjectedReturnService self;

  public SelfInjectedReturnService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void returnAll() {
    self.recordReturn();
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
