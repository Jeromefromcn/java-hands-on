package com.jerome.slledger.adapter.persistence.collateral;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Why "just {@code UPDATE balance = balance - x}" is not a design: 32 requests each want 10 from a
 * balance of 100, all having read the balance before any has written. Three ways to write the
 * update, one table each, no CHECK constraints so the wrong number can be read.
 */
@Timeout(60)
class BalanceUpdateIT {

  private static final int REQUESTS = 32;
  private static final BigDecimal EACH = new BigDecimal("10");
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static ConfigurableApplicationContext context;
  private static JdbcClient jdbc;
  private static TransactionTemplate transaction;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
    jdbc = context.getBean(JdbcClient.class);
    transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  @AfterAll
  static void stop() {
    context.close();
    Evidence.write("10-collateral-update.txt", EVIDENCE.toString());
  }

  private interface Withdrawal {
    /**
     * @return whether the request believes it took the money
     */
    boolean take(BigDecimal readBalance);
  }

  private BigDecimal run(String table, Withdrawal withdrawal, List<Boolean> outcomes)
      throws Exception {
    jdbc.sql("DROP TABLE IF EXISTS " + table).update();
    jdbc.sql("CREATE TABLE " + table + " (id int PRIMARY KEY, balance numeric(22,6) NOT NULL)")
        .update();
    jdbc.sql("INSERT INTO " + table + " VALUES (1, 100)").update();
    ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
    try {
      CyclicBarrier allHaveRead = new CyclicBarrier(REQUESTS);
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < REQUESTS; i++) {
        futures.add(
            pool.submit(
                () -> {
                  BigDecimal read =
                      jdbc.sql("SELECT balance FROM " + table + " WHERE id = 1")
                          .query(BigDecimal.class)
                          .single();
                  allHaveRead.await();
                  return transaction.execute(status -> withdrawal.take(read));
                }));
      }
      for (Future<Boolean> future : futures) {
        outcomes.add(future.get());
      }
    } finally {
      pool.shutdownNow();
    }
    return jdbc.sql("SELECT balance FROM " + table + " WHERE id = 1")
        .query(BigDecimal.class)
        .single();
  }

  private static long taken(List<Boolean> outcomes) {
    return outcomes.stream().filter(b -> b).count();
  }

  @Test
  @DisplayName(
      "read, check, write the computed balance: every request believes it took 10, the balance moves once")
  void reproducesLostUpdate() throws Exception {
    // See docs/decisions/10-messaging.md
    List<Boolean> outcomes = new ArrayList<>();

    BigDecimal balance =
        run(
            "bal_read_modify_write",
            read -> {
              if (read.compareTo(EACH) < 0) {
                return false;
              }
              jdbc.sql("UPDATE bal_read_modify_write SET balance = ? WHERE id = 1")
                  .param(read.subtract(EACH))
                  .update();
              return true;
            },
            outcomes);

    assertThat(taken(outcomes)).isEqualTo(REQUESTS);
    assertThat(balance).isEqualByComparingTo("90");
    EVIDENCE
        .append("read, check, write (balance - 10): ")
        .append(REQUESTS)
        .append(" requests, ")
        .append(taken(outcomes))
        .append(" told they took 10 (320 in total), final balance ")
        .append(balance.stripTrailingZeros().toPlainString())
        .append(" -> only one withdrawal is in the balance\n");
  }

  @Test
  @DisplayName(
      "read, check, then UPDATE balance = balance - 10: the decrement is atomic and the balance goes negative")
  void reproducesOverdraftWithBalanceMinusX() throws Exception {
    // See docs/decisions/10-messaging.md
    List<Boolean> outcomes = new ArrayList<>();

    BigDecimal balance =
        run(
            "bal_check_then_decrement",
            read -> {
              if (read.compareTo(EACH) < 0) {
                return false;
              }
              jdbc.sql("UPDATE bal_check_then_decrement SET balance = balance - ? WHERE id = 1")
                  .param(EACH)
                  .update();
              return true;
            },
            outcomes);

    assertThat(taken(outcomes)).isEqualTo(REQUESTS);
    assertThat(balance).isEqualByComparingTo("-220");
    EVIDENCE
        .append("read, check, then balance = balance - 10: ")
        .append(REQUESTS)
        .append(" requests, ")
        .append(taken(outcomes))
        .append(" took 10, final balance ")
        .append(balance.stripTrailingZeros().toPlainString())
        .append(" (100 - 320: overdrawn by 220)\n");
  }

  @Test
  @DisplayName(
      "UPDATE ... WHERE balance >= 10: exactly ten requests succeed and the balance is zero")
  void guardedUpdateIsExact() throws Exception {
    List<Boolean> outcomes = new ArrayList<>();

    BigDecimal balance =
        run(
            "bal_guarded",
            read ->
                jdbc.sql(
                            "UPDATE bal_guarded SET balance = balance - ? WHERE id = 1 AND balance >= ?")
                        .params(EACH, EACH)
                        .update()
                    == 1,
            outcomes);

    assertThat(taken(outcomes)).isEqualTo(10);
    assertThat(balance).isEqualByComparingTo("0");
    EVIDENCE
        .append("UPDATE ... WHERE balance >= 10 (the check and the write are one statement): ")
        .append(REQUESTS)
        .append(" requests, ")
        .append(taken(outcomes))
        .append(" took 10, final balance ")
        .append(balance.stripTrailingZeros().toPlainString())
        .append("; the other ")
        .append(REQUESTS - taken(outcomes))
        .append(" were refused\n");
  }
}
