package com.jerome.slledger.bootstrap.demo.tx.propagation;

import com.jerome.slledger.domain.error.DomainException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outer transaction: records the run, then accrues each position through a second bean, so each
 * call crosses a proxy and gets its own {@code REQUIRES_NEW} transaction.
 */
public class DailyAccrualBatch {

  private final JdbcClient jdbc;
  private final PositionAccrual positionAccrual;

  public DailyAccrualBatch(JdbcClient jdbc, PositionAccrual positionAccrual) {
    this.jdbc = jdbc;
    this.positionAccrual = positionAccrual;
  }

  /** Returns the ids of the positions domain refused; every other position is accrued. */
  @Transactional
  public List<String> run(LocalDate on) {
    jdbc.sql("INSERT INTO accrual_run(business_date) VALUES (?)").param(on).update();

    List<String> failed = new ArrayList<>();
    for (String id :
        jdbc.sql("SELECT id FROM loan_position ORDER BY id").query(String.class).list()) {
      try {
        positionAccrual.accrue(id, on);
      } catch (DomainException refused) {
        // The inner transaction has already rolled back on its own. This one is untouched: it
        // never saw the exception as its own, so it is not marked rollback-only.
        failed.add(id);
      }
    }
    return failed;
  }
}
