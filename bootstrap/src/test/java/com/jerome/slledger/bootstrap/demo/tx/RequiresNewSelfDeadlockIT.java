package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.bootstrap.demo.tx.deadlock.InnerAdjustment;
import com.jerome.slledger.bootstrap.demo.tx.deadlock.OuterAdjustment;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The outer transaction holds P1's row lock and calls a {@code REQUIRES_NEW} method that updates
 * the same row. The inner transaction waits on the outer; the outer waits on the inner method to
 * return. Only the inner's own {@code lock_timeout} ends it.
 */
@Timeout(30)
class RequiresNewSelfDeadlockIT {

  // Container start-up, including a first image pull, happens here: outside the test timeout.
  @BeforeAll
  static void startPostgres() {
    SharedPostgres.start();
  }

  private static final int POOL_SIZE = 3;
  private static final String[] PROPERTIES = {
    "spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
    "spring.datasource.hikari.minimum-idle=" + POOL_SIZE
  };

  private static final String ACTIVITY =
      """
      SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid) AS blocked_by,
             left(query, 90) AS query
        FROM pg_stat_activity
       WHERE datname = current_database() AND pid <> pg_backend_pid()
       ORDER BY pid""";

  private static final String LOCKS =
      """
      SELECT l.pid, l.locktype, l.relation::regclass AS relation, l.page, l.tuple,
             l.transactionid, l.mode, l.granted
        FROM pg_locks l
        JOIN pg_stat_activity a ON a.pid = l.pid
       WHERE a.datname = current_database() AND l.pid <> pg_backend_pid()
         AND l.locktype IN ('relation', 'tuple', 'transactionid')
         AND (l.locktype <> 'relation' OR l.relation = 'loan_position'::regclass)
       ORDER BY l.pid, l.granted DESC, l.locktype""";

  // Postgres sees one backend waiting on another's transaction and nothing more: the holder is
  // idle in transaction, not waiting on anything the database knows about. The other half of the
  // cycle is a Java thread waiting for a method to return. No deadlock detector can see it.
  // See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName(
      "REQUIRES_NEW on a row the outer transaction already locked waits on itself until"
          + " lock_timeout fails it with 55P03")
  void reproducesRequiresNewSelfDeadlock() throws Exception {
    try (var context = startWithP1()) {
      JdbcClient jdbc = context.getBean(JdbcClient.class);
      OuterAdjustment outer = context.getBean(OuterAdjustment.class);
      AtomicReference<Thread> worker = new AtomicReference<>();
      ExecutorService executor =
          Executors.newSingleThreadExecutor(
              task -> {
                Thread thread = new Thread(task, "self-deadlock-worker");
                worker.set(thread);
                return thread;
              });
      try {
        long started = System.nanoTime();
        Future<?> adjustment = executor.submit(() -> outer.adjust("P1"));

        assertThat(awaitBackendWaitingOnLock(jdbc)).as("a backend waits on a lock").isTrue();
        String stack = stackOf(worker.get());
        String activity = table(jdbc, ACTIVITY);
        String locks = table(jdbc, LOCKS);

        Throwable thrown = catchThrowable(() -> adjustment.get(20, TimeUnit.SECONDS));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(thrown.getCause()).isInstanceOf(DataAccessException.class);
        Throwable root = rootCause(thrown);
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("55P03");
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(3000);
        // The exception left the outer method too, so its decrement rolled back as well.
        assertThat(context.getBean(TxDemoRows.class).outstanding("P1")).isEqualTo(100);

        Evidence.write(
            "05-requires-new-self-deadlock.txt",
            "RequiresNewSelfDeadlockIT.reproducesRequiresNewSelfDeadlock\n"
                + "OuterAdjustment.adjust [REQUIRED] updates P1, then InnerAdjustment.adjust"
                + " [REQUIRES_NEW, SET LOCAL lock_timeout = '3s'] updates P1\n\n"
                + "elapsed until the worker's call failed (ms): "
                + elapsedMillis
                + "\nexception: "
                + thrown.getCause().getClass().getName()
                + "\nroot cause SQLState "
                + ((SQLException) root).getSQLState()
                + ": "
                + root.getMessage()
                + "\n\n--- pg_stat_activity while blocked ---\n"
                + activity
                + "\n--- pg_locks while blocked (loan_position, tuple, transactionid) ---\n"
                + locks
                + "\n--- worker thread stack while blocked ---\n"
                + stack);
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  @DisplayName(
      "the inner transaction's SET LOCAL lock_timeout does not leak into any pooled connection,"
          + " whether that transaction rolled back or committed")
  void lockTimeoutDoesNotLeakIntoThePool() throws Exception {
    try (var context = startWithP1()) {
      OuterAdjustment outer = context.getBean(OuterAdjustment.class);
      assertThat(catchThrowable(() -> outer.adjust("P1"))).isInstanceOf(DataAccessException.class);
      // The path above ends in a rollback, and Postgres reverts even a plain SET on rollback, so
      // on its own it cannot tell SET LOCAL from SET. A committed inner transaction can: called
      // directly, with no outer lock in the way, it commits, and a plain SET would stay behind.
      context.getBean(InnerAdjustment.class).adjust("P1");
      assertThat(context.getBean(TxDemoRows.class).outstanding("P1")).isEqualTo(99);

      // Borrow every connection in the pool at once, so the one the inner transaction used is
      // certainly among them, and ask each a fresh transaction's lock_timeout.
      DataSource dataSource = context.getBean(DataSource.class);
      List<Connection> borrowed = new ArrayList<>();
      try {
        for (int i = 0; i < POOL_SIZE; i++) {
          borrowed.add(dataSource.getConnection());
        }
        for (Connection connection : borrowed) {
          connection.setAutoCommit(false);
          try (Statement statement = connection.createStatement();
              ResultSet rs = statement.executeQuery("SHOW lock_timeout")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("0");
          }
          connection.rollback();
        }
      } finally {
        for (Connection connection : borrowed) {
          connection.close();
        }
      }
    }
  }

  private static ConfigurableApplicationContext startWithP1() {
    ConfigurableApplicationContext context =
        TxDemo.start(PROPERTIES, OuterAdjustment.class, InnerAdjustment.class);
    context.getBean(TxDemoRows.class).insertPosition("P1", 100);
    return context;
  }

  /**
   * Observation only, not ordering: the scenario orders itself (the inner UPDATE can only run after
   * the outer one). This waits, bounded at 50 x 100 ms, for the moment worth recording.
   */
  private static boolean awaitBackendWaitingOnLock(JdbcClient jdbc) throws InterruptedException {
    for (int attempt = 0; attempt < 50; attempt++) {
      long waiting =
          jdbc.sql(
                  "SELECT count(*) FROM pg_stat_activity"
                      + " WHERE datname = current_database() AND wait_event_type = 'Lock'")
              .query(Long.class)
              .single();
      if (waiting > 0) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return false;
  }

  private static String stackOf(Thread thread) {
    ThreadInfo info =
        ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId(), Integer.MAX_VALUE);
    // ThreadInfo.toString() stops after 8 frames; the frames that matter are further down.
    StringBuilder out =
        new StringBuilder("\"" + info.getThreadName() + "\" " + info.getThreadState() + "\n");
    for (StackTraceElement frame : info.getStackTrace()) {
      out.append("    at ").append(frame).append("\n");
    }
    return out.toString();
  }

  private static String table(JdbcClient jdbc, String sql) {
    StringBuilder out = new StringBuilder();
    for (Map<String, Object> row : jdbc.sql(sql).query().listOfRows()) {
      out.append(row).append("\n");
    }
    return out.toString();
  }

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }
}
