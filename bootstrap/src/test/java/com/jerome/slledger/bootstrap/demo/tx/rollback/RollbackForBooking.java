package com.jerome.slledger.bootstrap.demo.tx.rollback;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.springframework.transaction.annotation.Transactional;

public class RollbackForBooking {

  private final TxDemoRows rows;

  public RollbackForBooking(TxDemoRows rows) {
    this.rows = rows;
  }

  @Transactional(rollbackFor = SettlementRejectedException.class)
  public void book(String positionId) throws SettlementRejectedException {
    rows.insertTrade(positionId, 10);
    throw new SettlementRejectedException(positionId);
  }
}
