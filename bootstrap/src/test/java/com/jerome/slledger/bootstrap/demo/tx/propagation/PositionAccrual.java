package com.jerome.slledger.bootstrap.demo.tx.propagation;

import com.jerome.slledger.bootstrap.demo.tx.support.PositionAccrualStep;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One position's accrual, in its own transaction, independent of the batch around it. */
public class PositionAccrual {

  private final PositionAccrualStep step;
  private final DataSource dataSource;
  private volatile int activeConnectionsInside;

  public PositionAccrual(PositionAccrualStep step, DataSource dataSource) {
    this.step = step;
    this.dataSource = dataSource;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void accrue(String id, LocalDate on) {
    // The transaction manager bound this transaction's connection when it began, so the count
    // already includes it here, before the step runs its first statement.
    activeConnectionsInside =
        ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
    step.accrue(id, on);
  }

  public int activeConnectionsInside() {
    return activeConnectionsInside;
  }
}
