package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class OptimisticLoanPositionRepositoryIT extends LoanPositionRepositoryContract {

  @Override
  Class<?>[] beans() {
    return new Class<?>[] {OptimisticLoanPositionRepository.class, UnitOfWorkPositions.class};
  }

  @Test
  void eachWriteBackAdvancesTheVersion() {
    inTx(() -> add(positionOf("P1", CP1, 100)));
    assertThat(version("P1")).isZero();

    inTx(
        () -> {
          LoanPosition position = repository.find(new LoanPositionId("P1")).orElseThrow();
          position.returnQuantity(30);
          return add(position);
        });

    assertThat(version("P1")).isEqualTo(1);
  }

  /**
   * T1 loads P1 at 100. T2 loads it, returns 30, commits: 70. T3 returns 10 on T1's stale copy and
   * saves it. The naive adapter writes 90 and raises nothing; T2's return is gone. See
   * docs/decisions/06-persistence.md.
   */
  @Test
  void reproducesSilentOverwriteAcrossTransactions() {
    try (var naive = LedgerDb.start(NaiveLoanPositionRepository.class)) {
      LoanPositionRepository naiveRepository = naive.getBean(LoanPositionRepository.class);
      var naiveTx = naive.getBean(TransactionTemplate.class);
      var naiveJdbc = naive.getBean(JdbcClient.class);
      copyReferenceRows(naiveJdbc);
      naiveTx.executeWithoutResult(s -> naiveRepository.add(positionOf("P1", CP1, 100)));

      LoanPosition t1 =
          naiveTx.execute(s -> naiveRepository.find(new LoanPositionId("P1")).orElseThrow());
      naiveTx.executeWithoutResult(
          s -> {
            LoanPosition t2 = naiveRepository.find(new LoanPositionId("P1")).orElseThrow();
            t2.returnQuantity(30);
            naiveRepository.add(t2);
          });
      naiveTx.executeWithoutResult(
          s -> {
            t1.returnQuantity(10);
            naiveRepository.add(t1);
          });

      assertThat(
              naiveJdbc
                  .sql("SELECT outstanding_quantity FROM loan_position WHERE id = 'P1'")
                  .query(Long.class)
                  .single())
          .isEqualTo(90);
    }
  }

  @Test
  void theSameSequenceIsRefusedAtTheStaleWrite() {
    inTx(() -> add(positionOf("P1", CP1, 100)));

    LoanPosition t1 = inTx(() -> repository.find(new LoanPositionId("P1")).orElseThrow());
    inTx(
        () -> {
          LoanPosition t2 = repository.find(new LoanPositionId("P1")).orElseThrow();
          t2.returnQuantity(30);
          return add(t2);
        });

    assertThatExceptionOfType(PositionNotLoadedInThisTransactionException.class)
        .isThrownBy(
            () ->
                inTx(
                    () -> {
                      t1.returnQuantity(10);
                      return add(t1);
                    }));
    assertThat(outstanding("P1")).isEqualTo(70);
  }

  private long version(String id) {
    return jdbc.sql("SELECT version FROM loan_position WHERE id = ?")
        .param(id)
        .query(Long.class)
        .single();
  }

  /** The naive context has a database of its own; it needs the same reference rows. */
  private static void copyReferenceRows(JdbcClient target) {
    target
        .sql(
            "INSERT INTO counterparty VALUES ('CP-001', 'Synthetic Borrower CP-001',"
                + " 100000000.00, 'HKD')")
        .update();
    target
        .sql(
            "INSERT INTO security VALUES ('HK0000000001', 'Synthetic Equity', 'HKD', 41.26,"
                + " 'HKD')")
        .update();
  }
}
