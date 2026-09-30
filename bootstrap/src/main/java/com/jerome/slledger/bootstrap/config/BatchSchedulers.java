package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.reconciliation.SettlementRecovery;
import com.jerome.slledger.application.revaluation.RevaluationDispatcher;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunDailyAccrual;
import com.jerome.slledger.batch.retention.RunRetentionJob;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The timers batch needs: the day's accrual and the retention job at a time of day, and the two
 * pollers (revaluation requests, settlement retries) at an interval. One thread, started with the
 * context and stopped with it, and only created when batch and its schedulers are on.
 *
 * <p>Nothing here decides anything a job does not: the accrual and retention are launched for a
 * date and the framework makes a repeat a no-op; the dispatcher and the ladder claim what is due.
 * An exception never ends a schedule ({@code scheduleWithFixedDelay} cancels itself if a run
 * throws), so each task is wrapped.
 *
 * <p>Reconciliation is not scheduled: it needs a file from outside and is launched by a person or
 * by whatever delivers the file, with the command runner.
 */
class BatchSchedulers implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(BatchSchedulers.class);
  static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");

  private final Clock clock;
  private final RunDailyAccrual fencedAccrual;
  private final RunAccrual unfencedAccrual;
  private final RunRetentionJob retention;
  private final RevaluationDispatcher dispatcher;
  private final SettlementRecovery ladder;
  private final LocalTime accrualTime;
  private final LocalTime retentionTime;
  private final Duration dispatchInterval;
  private final Duration ladderInterval;

  private ScheduledExecutorService executor;

  BatchSchedulers(
      Clock clock,
      RunDailyAccrual fencedAccrual,
      RunAccrual unfencedAccrual,
      RunRetentionJob retention,
      RevaluationDispatcher dispatcher,
      SettlementRecovery ladder,
      LocalTime accrualTime,
      LocalTime retentionTime,
      Duration dispatchInterval,
      Duration ladderInterval) {
    this.clock = clock;
    this.fencedAccrual = fencedAccrual;
    this.unfencedAccrual = unfencedAccrual;
    this.retention = retention;
    this.dispatcher = dispatcher;
    this.ladder = ladder;
    this.accrualTime = accrualTime;
    this.retentionTime = retentionTime;
    this.dispatchInterval = dispatchInterval;
    this.ladderInterval = ladderInterval;
  }

  /** How long from {@code now} until the next time of day {@code at} in the business zone. */
  static Duration untilNext(Clock clock, LocalTime at) {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(BUSINESS_ZONE));
    ZonedDateTime next = now.with(at);
    if (!next.isAfter(now)) {
      next = next.plusDays(1);
    }
    return Duration.between(now, next);
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newScheduledThreadPool(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "sl-batch-scheduler");
              thread.setDaemon(true);
              return thread;
            });
    if (fencedAccrual == null && unfencedAccrual == null) {
      LOG.error("No accrual scheduled: batch is on but there is no accrual launcher");
    } else if (fencedAccrual == null) {
      LOG.warn(
          "The daily accrual is scheduled WITHOUT a lock: ledger.cache.enabled is off, so two"
              + " instances would both run it. Run it on one instance, or enable the cache (Redis).");
    }
    daily("accrual", accrualTime, this::accrue);
    daily("retention", retentionTime, this::retain);
    if (dispatcher != null) { // only with messaging on: a margin call is an outbox row
      executor.scheduleWithFixedDelay(
          guard("revaluation dispatch", () -> dispatcher.dispatch(20)),
          dispatchInterval.toMillis(),
          dispatchInterval.toMillis(),
          TimeUnit.MILLISECONDS);
    }
    executor.scheduleWithFixedDelay(
        guard("settlement ladder", ladder::run),
        ladderInterval.toMillis(),
        ladderInterval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void daily(String name, LocalTime at, Runnable task) {
    Duration wait = untilNext(clock, at);
    executor.schedule(
        () -> {
          guard(name, task).run();
          synchronized (this) {
            if (executor != null) {
              daily(name, at, task);
            }
          }
        },
        wait.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private Runnable guard(String name, Runnable task) {
    return () -> {
      try {
        task.run();
      } catch (RuntimeException e) {
        LOG.error("Scheduled {} failed", name, e);
      }
    };
  }

  private void accrue() {
    LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
    Object outcome =
        fencedAccrual != null
            ? fencedAccrual.run(today).status()
            : unfencedAccrual != null ? unfencedAccrual.run(today).status() : "not configured";
    LOG.info("Daily accrual for {}: {}", today, outcome);
  }

  private void retain() {
    LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
    LOG.info("Retention for {}: {}", today, retention.run(today).status());
  }

  @Override
  public synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
