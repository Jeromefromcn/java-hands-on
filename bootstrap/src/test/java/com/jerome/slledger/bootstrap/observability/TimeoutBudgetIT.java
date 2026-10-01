package com.jerome.slledger.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.bootstrap.config.TimeoutBudget;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The budget in a running application, and the database's side of it. */
@Timeout(180)
class TimeoutBudgetIT {

  @Test
  @DisplayName("the application prints its budget and starts; a broken budget refuses to start")
  void budgetIsCheckedAtStartUp() {
    try (ConfigurableApplicationContext app = LedgerApp.start()) {
      TimeoutBudget budget = app.getBean(TimeoutBudget.class);
      assertThat(budget.problems()).isEmpty();
      Evidence.write("12-timeout-budget.txt", budget.table());
    }

    assertThatThrownBy(() -> LedgerApp.start("--ledger.marketdata.executor.deadline=2s").close())
        .hasStackTraceContaining("The timeout budget does not hold")
        .hasStackTraceContaining("fan-out deadline");
  }

  @Test
  @DisplayName("the database cancels a statement at the configured timeout")
  void statementTimeoutIsHonoured() {
    try (ConfigurableApplicationContext app =
        LedgerApp.start("--ledger.timeouts.db-statement=1s")) {
      JdbcClient jdbc = app.getBean(JdbcClient.class);

      long start = System.nanoTime();
      assertThatThrownBy(() -> jdbc.sql("SELECT pg_sleep(5)").query().singleRow())
          .hasStackTraceContaining("statement timeout");
      long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();

      Evidence.write(
          "12-statement-timeout.txt",
          "SELECT pg_sleep(5) with ledger.timeouts.db-statement=1s was cancelled after "
              + millis
              + " ms\n");
      assertThat(millis).isBetween(900L, 2_500L);
    }
  }
}
