package com.jerome.slledger.bootstrap.config;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the logging context (the MDC: trace id and span id) from the thread that submits a task
 * to the thread that runs it. The MDC belongs to a thread, not to the work; without this a pooled
 * thread logs whatever its previous task left, or nothing, and the lines of one request stop
 * sharing an id the moment it crosses an executor.
 *
 * <p>The executing thread's own MDC is put back afterwards, not cleared: under a caller-runs policy
 * the task executes on the submitter's thread, and clearing would drop its trace id mid-request.
 */
public final class MdcPropagation {

  private MdcPropagation() {}

  /** The task, running with the MDC of the thread that calls this. */
  public static Runnable carrying(Runnable task) {
    Map<String, String> captured = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> before = MDC.getCopyOfContextMap();
      restore(captured);
      try {
        task.run();
      } finally {
        restore(before);
      }
    };
  }

  /**
   * For every executor Spring creates or is handed: {@code
   * ThreadPoolTaskExecutor.setTaskDecorator}.
   */
  public static TaskDecorator decorator() {
    return MdcPropagation::carrying;
  }

  private static void restore(Map<String, String> context) {
    if (context == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(context);
    }
  }
}
