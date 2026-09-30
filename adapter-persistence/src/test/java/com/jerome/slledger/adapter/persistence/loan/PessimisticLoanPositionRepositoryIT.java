package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.SqlRecorder;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.PessimisticLockingFailureException;

class PessimisticLoanPositionRepositoryIT extends LoanPositionRepositoryContract {

  @Override
  String[] properties() {
    return new String[] {"ledger.persistence.locking=pessimistic", SqlRecorder.PROPERTY};
  }

  @Test
  void theSelectedAdapterIsThePessimisticOne() {
    assertThat(context.getBeansOfType(LoanPositionRepository.class).values())
        .singleElement()
        .isInstanceOf(PessimisticLoanPositionRepository.class);
  }

  @Test
  void findLocksTheRow() {
    inTx(() -> add(positionOf("P1", CP1, 100)));
    SqlRecorder.clear();

    inTx(() -> repository.find(new LoanPositionId("P1")));

    List<String> sql = SqlRecorder.statements();
    assertThat(sql).anyMatch(s -> s.contains("for no key update") || s.contains("for update"));
    Evidence.write(
        "06-pessimistic-sql.txt",
        String.join(System.lineSeparator(), sql) + System.lineSeparator());
  }

  /** A second finder waits for the first to commit, bounded by the lock timeout. */
  @Test
  @Timeout(30)
  void aSecondFinderGivesUpAfterTheLockTimeout() throws Exception {
    inTx(() -> add(positionOf("P1", CP1, 100)));
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService holder = Executors.newSingleThreadExecutor();
    try {
      Future<?> holding =
          holder.submit(
              () ->
                  inTx(
                      () -> {
                        repository.find(new LoanPositionId("P1"));
                        locked.countDown();
                        try {
                          release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return null;
                      }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

      long started = System.nanoTime();
      Throwable failure = null;
      try {
        inTx(() -> repository.find(new LoanPositionId("P1")));
      } catch (RuntimeException e) {
        failure = e;
      }
      long waitedMillis = (System.nanoTime() - started) / 1_000_000;
      release.countDown();
      holding.get(10, TimeUnit.SECONDS);

      assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
      assertThat(waitedMillis).isGreaterThanOrEqualTo(3000);
      Evidence.write(
          "06-pessimistic-timeout.txt",
          "waited %d ms, then %s: %s%n"
              .formatted(
                  waitedMillis,
                  failure.getClass().getName(),
                  String.valueOf(failure.getMessage()).lines().findFirst().orElse("")));
    } finally {
      release.countDown();
      holder.shutdownNow();
    }
  }
}
