package com.jerome.slledger.bootstrap.demo.tx.rollback;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.springframework.transaction.annotation.Transactional;

public class DefaultRollbackBooking {

  private final TxDemoRows rows;

  public DefaultRollbackBooking(TxDemoRows rows) {
    this.rows = rows;
  }

  @Transactional
  public void book(String positionId) throws SettlementRejectedException {
    rows.insertTrade(positionId, 10);
    throw new SettlementRejectedException(positionId);
  }
}
