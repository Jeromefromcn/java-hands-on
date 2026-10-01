package com.jerome.slledger.batch.accrual;

import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The threads that run an accrual's partitions. A holder rather than a {@code TaskExecutor} bean:
 * an executor in the context is picked up by anything that asks for one by type, and this one is
 * for partitions only. Threads are created on first use, so an unpartitioned accrual never starts
 * one.
 */
public final class AccrualPartitionPool implements AutoCloseable {

  private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

  public AccrualPartitionPool(int threads) {
    this(threads, null);
  }

  /**
   * @param decorator wraps each task as it is submitted; null for none
   */
  public AccrualPartitionPool(int threads, TaskDecorator decorator) {
    if (decorator != null) {
      executor.setTaskDecorator(decorator);
    }
    executor.setCorePoolSize(threads);
    executor.setMaxPoolSize(threads);
    executor.setThreadNamePrefix("sl-accrual-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.initialize();
  }

  public ThreadPoolTaskExecutor executor() {
    return executor;
  }

  @Override
  public void close() {
    executor.shutdown();
  }
}
