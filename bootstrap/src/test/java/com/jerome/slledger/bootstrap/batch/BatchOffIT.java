package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.reconciliation.SettlementRecovery;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.context.ConfigurableApplicationContext;

/** With batch off, nothing of it exists: no repository, no job, no launcher, no thread. */
@Timeout(120)
class BatchOffIT {

  @Test
  @DisplayName(
      "with ledger.batch.enabled unset there is no job repository, no launcher and no scheduler thread")
  void nothingExists() {
    try (ConfigurableApplicationContext app = LedgerApp.start()) {
      assertThat(app.getBeansOfType(JobOperator.class)).isEmpty();
      assertThat(app.getBeansOfType(RunAccrual.class)).isEmpty();
      assertThat(app.getBeansOfType(SettlementRecovery.class)).isEmpty();
      Set<String> threads =
          Thread.getAllStackTraces().keySet().stream()
              .map(Thread::getName)
              .collect(Collectors.toSet());
      assertThat(threads).doesNotContain("sl-batch-scheduler");
      assertThat(threads.stream().filter(t -> t.startsWith("sl-accrual-"))).isEmpty();
    }
  }

  @Test
  @DisplayName("with batch on and the timers off, no scheduler thread runs either")
  void timersAreOffByDefault() {
    try (BatchLedger ledger = BatchLedger.start()) {
      Set<String> threads =
          Thread.getAllStackTraces().keySet().stream()
              .map(Thread::getName)
              .collect(Collectors.toSet());
      assertThat(threads).doesNotContain("sl-batch-scheduler");
      assertThat(ledger.app.getBeansOfType(RunAccrual.class)).hasSize(1);
    }
  }
}
