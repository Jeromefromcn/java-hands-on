package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.messaging.DedupPurge;
import com.jerome.slledger.application.messaging.OutboxRelay;
import com.jerome.slledger.application.messaging.OutboxStore;
import com.jerome.slledger.application.messaging.RelayResult;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The two timers messaging needs: the outbox relay, and the purge of consumed message ids. One
 * thread each, started with the context and stopped with it, and only created when messaging is on.
 *
 * <p>A relay pass that finds work goes round again straight away until the outbox is empty or a
 * send fails; an idle relay waits {@code relayInterval}. A pass that fails is logged with the age
 * of the oldest unpublished message, which is how a relay that is failing looks from outside. An
 * exception never ends the schedule: {@code scheduleWithFixedDelay} cancels itself if a run throws.
 */
class MessagingSchedulers implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(MessagingSchedulers.class);

  private final OutboxRelay relay;
  private final OutboxStore store;
  private final DedupPurge purge;
  private final Clock clock;
  private final Duration relayInterval;
  private final Duration purgeInterval;
  private final boolean relayEnabled;

  private ScheduledExecutorService executor;

  MessagingSchedulers(
      OutboxRelay relay,
      OutboxStore store,
      DedupPurge purge,
      Clock clock,
      Duration relayInterval,
      Duration purgeInterval,
      boolean relayEnabled) {
    this.relay = relay;
    this.store = store;
    this.purge = purge;
    this.clock = clock;
    this.relayInterval = relayInterval;
    this.purgeInterval = purgeInterval;
    this.relayEnabled = relayEnabled;
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newScheduledThreadPool(
            2,
            runnable -> {
              Thread thread = new Thread(runnable, "sl-messaging-scheduler");
              thread.setDaemon(true);
              return thread;
            });
    if (relayEnabled) {
      executor.scheduleWithFixedDelay(
          this::relayPass,
          relayInterval.toMillis(),
          relayInterval.toMillis(),
          TimeUnit.MILLISECONDS);
    }
    executor.scheduleWithFixedDelay(
        this::purgePass, purgeInterval.toMillis(), purgeInterval.toMillis(), TimeUnit.MILLISECONDS);
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

  void relayPass() {
    try {
      RelayResult result;
      do {
        result = relay.relayBatch();
        if (!result.complete()) {
          LOG.warn(
              "Outbox relay stopped after {} of {} messages: {}; oldest unpublished message is {} old",
              result.sent(),
              result.claimed(),
              result.failure().getMessage(),
              store
                  .oldestUnpublishedSince()
                  .map(since -> Duration.between(since, clock.instant()))
                  .orElse(Duration.ZERO));
        }
      } while (result.complete() && result.claimed() > 0);
    } catch (RuntimeException e) {
      LOG.error("Outbox relay pass failed", e);
    }
  }

  void purgePass() {
    try {
      int purged = purge.run();
      if (purged > 0) {
        LOG.info("Forgot {} consumed message ids past the dedup TTL", purged);
      }
    } catch (RuntimeException e) {
      LOG.error("Dedup purge failed", e);
    }
  }
}
