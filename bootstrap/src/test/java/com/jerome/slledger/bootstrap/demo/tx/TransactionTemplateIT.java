package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.AccrualEntryRows;
import com.jerome.slledger.bootstrap.demo.tx.support.PositionAccrualStep;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import com.jerome.slledger.bootstrap.demo.tx.template.TemplateDailyAccrualBatch;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code RequiresNewAccrualIT}'s batch again, with each position's {@code REQUIRES_NEW} boundary
 * drawn by a {@code TransactionTemplate} inside the loop instead of by a second bean's annotation.
 */
class TransactionTemplateIT {

  private static final LocalDate ON = LocalDate.of(2026, 3, 16);

  @Test
  @DisplayName(
      "a TransactionTemplate per position gives the same outcome as the REQUIRES_NEW bean:"
          + " P3 fails, P1 P2 P4 P5 commit, one run row")
  void templateGivesTheSameOutcome() {
    try (var context =
        TxDemo.start(
            TemplateDailyAccrualBatch.class, PositionAccrualStep.class, AccrualEntryRows.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      // Same data as RequiresNewAccrualIT: P3's negative quantity is refused by domain.
      rows.insertPosition("P1", 1000);
      rows.insertPosition("P2", 2000);
      rows.insertPosition("P3", -1);
      rows.insertPosition("P4", 4000);
      rows.insertPosition("P5", 5000);

      List<String> failed = context.getBean(TemplateDailyAccrualBatch.class).run(ON);

      assertThat(failed).containsExactly("P3");
      assertThat(
              context
                  .getBean(JdbcClient.class)
                  .sql("SELECT position_id FROM accrual_entry ORDER BY position_id")
                  .query(String.class)
                  .list())
          .containsExactly("P1", "P2", "P4", "P5");
      assertThat(rows.accrualRunCount()).isEqualTo(1);
    }
  }
}
