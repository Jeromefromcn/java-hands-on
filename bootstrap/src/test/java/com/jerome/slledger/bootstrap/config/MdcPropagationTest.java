package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The MDC belongs to a thread. What a pooled thread logs under depends on whom it was handed. */
@Timeout(30)
class MdcPropagationTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private ThreadPoolTaskExecutor pool;

  private ThreadPoolTaskExecutor pool(boolean decorated) {
    pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(1);
    pool.setMaxPoolSize(1);
    if (decorated) {
      pool.setTaskDecorator(MdcPropagation.decorator());
    }
    pool.initialize();
    return pool;
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
    if (pool != null) {
      pool.shutdown();
    }
  }

  private static String traceIdSeenByPooledThread(ThreadPoolTaskExecutor pool) throws Exception {
    MDC.put("traceId", TRACE_ID);
    Future<String> seen = pool.submit(() -> String.valueOf(MDC.get("traceId")));
    return seen.get(10, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("a pooled thread logs with no trace id: the MDC stayed on the submitting thread")
  void reproducesMdcLostOnPooledThread() throws Exception {
    // See docs/decisions/12-resilience-observability.md
    String seen = traceIdSeenByPooledThread(pool(false));

    assertThat(seen).isEqualTo("null");
    Evidence.write(
        "12-mdc-pool-undecorated.txt",
        "undecorated pool: traceId seen by the worker = " + seen + "\n");
  }

  @Test
  @DisplayName(
      "with the decorator the task runs with the submitter's trace id, and leaves nothing behind")
  void decoratorCarriesTheMdcAndRestoresTheWorkers() throws Exception {
    ThreadPoolTaskExecutor decorated = pool(true);

    String seen = traceIdSeenByPooledThread(decorated);
    MDC.clear();
    // The same single worker, now handed a task from a thread that has no trace: it must not still
    // hold the previous task's id.
    String next =
        decorated.submit(() -> String.valueOf(MDC.get("traceId"))).get(10, TimeUnit.SECONDS);

    assertThat(seen).isEqualTo(TRACE_ID);
    assertThat(next).isEqualTo("null");
    Evidence.write(
        "12-mdc-pool-decorated.txt",
        "decorated pool: traceId seen by the worker = "
            + seen
            + "; next task from a thread with no trace sees "
            + next
            + "\n");
  }

  @Test
  @DisplayName("run on the submitter's own thread, the submitter's MDC is restored, not cleared")
  void callerRunsKeepsTheSubmittersContext() {
    MDC.put("traceId", TRACE_ID);

    MdcPropagation.carrying(() -> MDC.put("traceId", "overwritten-inside")).run();

    assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID);
  }
}
