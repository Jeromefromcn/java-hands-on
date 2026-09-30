package com.jerome.slledger.bootstrap.config;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.MDC;

/**
 * The bounded pool behind the price fan-out: named threads, a bounded queue, a configured rejection
 * policy, and counters the JDK does not keep (rejections).
 *
 * <p>Implements {@link Executor} only for the use cases; the concrete pool stays here, so nothing
 * outside this class casts to reach a counter.
 */
public final class MarketDataPool implements Executor, AutoCloseable {

  private final ThreadPoolExecutor pool;
  private final AtomicLong rejected = new AtomicLong();

  public MarketDataPool(MarketDataExecutorProperties props) {
    AtomicInteger sequence = new AtomicInteger();
    ThreadFactory threads =
        task -> {
          Thread thread = new Thread(task, "market-data-" + sequence.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    RejectedExecutionHandler policy = handlerFor(props.policy());
    this.pool =
        new ThreadPoolExecutor(
            props.core(),
            props.max(),
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(props.queue()),
            threads,
            (task, executor) -> {
              rejected.incrementAndGet();
              policy.rejectedExecution(task, executor);
            });
  }

  private static RejectedExecutionHandler handlerFor(MarketDataExecutorProperties.Policy policy) {
    return switch (policy) {
      case ABORT -> new ThreadPoolExecutor.AbortPolicy();
      case CALLER_RUNS -> new ThreadPoolExecutor.CallerRunsPolicy();
      case DISCARD -> new ThreadPoolExecutor.DiscardPolicy();
      case DISCARD_OLDEST -> new ThreadPoolExecutor.DiscardOldestPolicy();
    };
  }

  @Override
  public void execute(Runnable task) {
    pool.execute(withSubmittersMdc(task));
  }

  /**
   * The task with the submitting thread's MDC, so its log lines carry the request's trace id. The
   * executing thread's own MDC is put back afterwards rather than cleared: under caller-runs that
   * thread is the request's, and clearing would drop its trace id mid-request.
   */
  private static Runnable withSubmittersMdc(Runnable task) {
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

  private static void restore(Map<String, String> context) {
    if (context == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(context);
    }
  }

  public PoolStats stats() {
    return new PoolStats(
        pool.getPoolSize(),
        pool.getLargestPoolSize(),
        pool.getActiveCount(),
        pool.getQueue().size(),
        pool.getCompletedTaskCount(),
        rejected.get());
  }

  @Override
  public void close() {
    pool.shutdownNow();
  }
}
