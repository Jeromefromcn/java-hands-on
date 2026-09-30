package com.jerome.slledger.bootstrap.demo.events;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A write in a transaction of its own: what an AFTER_COMMIT listener has to do to persist. */
public class NewTransactionWriter {

  private final TxDemoRows rows;

  public NewTransactionWriter(TxDemoRows rows) {
    this.rows = rows;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(String positionId, java.util.List<String> transactionNames) {
    transactionNames.add(
        String.valueOf(
            org.springframework.transaction.support.TransactionSynchronizationManager
                .getCurrentTransactionName()));
    rows.insertTrade(positionId, 2);
  }
}
