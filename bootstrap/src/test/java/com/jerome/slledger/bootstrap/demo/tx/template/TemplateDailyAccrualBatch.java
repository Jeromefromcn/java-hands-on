package com.jerome.slledger.bootstrap.demo.tx.template;

import com.jerome.slledger.bootstrap.demo.tx.support.PositionAccrualStep;
import com.jerome.slledger.domain.error.DomainException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily accrual batch with the per-position boundary drawn inside the loop. One bean: the
 * template is not a proxy, so there is no self-invocation to dodge and no second bean to split out.
 */
public class TemplateDailyAccrualBatch {

  private final JdbcClient jdbc;
  private final PositionAccrualStep step;
  private final TransactionTemplate requiresNew;

  public TemplateDailyAccrualBatch(
      JdbcClient jdbc, PositionAccrualStep step, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.step = step;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Returns the ids of the positions domain refused; every other position is accrued. */
  @Transactional
  public List<String> run(LocalDate on) {
    jdbc.sql("INSERT INTO accrual_run(business_date) VALUES (?)").param(on).update();

    List<String> failed = new ArrayList<>();
    for (String id :
        jdbc.sql("SELECT id FROM loan_position ORDER BY id").query(String.class).list()) {
      try {
        requiresNew.executeWithoutResult(status -> step.accrue(id, on));
      } catch (DomainException refused) {
        // The template rolled its own transaction back before rethrowing; this one is untouched.
        failed.add(id);
      }
    }
    return failed;
  }
}
