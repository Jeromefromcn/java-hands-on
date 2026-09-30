package com.jerome.slledger.bootstrap.config;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.MDC;

/**
 * The executor behind the price fan-out, in one of two shapes chosen by configuration.
 *
 * <ul>
 *   <li><b>platform</b>: a bounded {@link ThreadPoolExecutor} with a bounded queue and the
 *       configured rejection policy.
 *   <li><b>virtual</b>: a thread per task on virtual threads, behind a {@link Semaphore} of {@code
 *       max-concurrent} permits. Virtual threads are cheap to create, so the pool's size stops
 *       being the limit; without a limit the source's slowness would simply move the failure from
 *       Tomcat to the source. A task that finds no permit is refused, as {@code abort} refuses.
 * </ul>
 *
 * Both count rejections, which the JDK does not, and both carry the submitter's MDC into the task.
 * Implements {@link Executor} only for the use cases; nothing outside this class casts to reach a
 * counter.
 */
public final class MarketDataPool implements Executor, AutoCloseable {

  private final ThreadPoolExecutor pool;
  private final ExecutorService virtual;
  private final Semaphore permits;
  private final int maxConcurrent;
  private final AtomicInteger peak = new AtomicInteger();
  private final AtomicLong completed = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();

  public MarketDataPool(MarketDataExecutorProperties props) {
    if (props.type() == MarketDataExecutorProperties.Type.VIRTUAL) {
      this.pool = null;
      this.virtual =
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("market-data-v-", 1).factory());
      this.permits = new Semaphore(props.maxConcurrent());
      this.maxConcurrent = props.maxConcurrent();
    } else {
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
      this.virtual = null;
      this.permits = null;
      this.maxConcurrent = 0;
    }
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
    Runnable carrying = withSubmittersMdc(task);
    if (pool != null) {
      pool.execute(carrying);
      return;
    }
    if (!permits.tryAcquire()) {
      rejected.incrementAndGet();
      throw new RejectedExecutionException("all " + maxConcurrent + " concurrent slots are taken");
    }
    peak.accumulateAndGet(maxConcurrent - permits.availablePermits(), Math::max);
    try {
      virtual.execute(
          () -> {
            try {
              carrying.run();
            } finally {
              completed.incrementAndGet();
              permits.release();
            }
          });
    } catch (RuntimeException notStarted) {
      permits.release();
      throw notStarted;
    }
  }

  /** For the virtual shape, {@code poolSize} is the concurrency in use and nothing is queued. */
  public PoolStats stats() {
    if (pool != null) {
      return new PoolStats(
          pool.getPoolSize(),
          pool.getLargestPoolSize(),
          pool.getActiveCount(),
          pool.getQueue().size(),
          pool.getCompletedTaskCount(),
          rejected.get());
    }
    int inUse = maxConcurrent - permits.availablePermits();
    return new PoolStats(inUse, peak.get(), inUse, 0, completed.get(), rejected.get());
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

  @Override
  public void close() {
    if (pool != null) {
      pool.shutdownNow();
    } else {
      virtual.shutdownNow();
    }
  }
}
