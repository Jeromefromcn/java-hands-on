package com.jerome.slledger.application.messaging;

import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.NoticeChannel.MarginCallNotice;
import com.jerome.slledger.application.observability.LedgerMetrics;

/**
 * Tells a counterparty about a margin call, once per event. The event's id is the dedup key, so the
 * same message delivered again (a relay resend, a rebalance, a replayed offset) sends nothing.
 */
public class MarginCallNotifier {

  public static final String CONSUMER = "margin-call-notifier";

  private final IdempotentConsumer guard;
  private final NoticeChannel channel;
  private final LedgerMetrics metrics;

  public MarginCallNotifier(IdempotentConsumer guard, NoticeChannel channel) {
    this(guard, channel, LedgerMetrics.NONE);
  }

  public MarginCallNotifier(
      IdempotentConsumer guard, NoticeChannel channel, LedgerMetrics metrics) {
    this.guard = guard;
    this.channel = channel;
    this.metrics = metrics;
  }

  /**
   * @return {@code true} if a notice was sent, {@code false} if this event had already been handled
   */
  public boolean handle(MarginCallRaised event) {
    boolean sent =
        guard.once(
            CONSUMER,
            event.id(),
            () ->
                channel.send(
                    new MarginCallNotice(
                        event.counterparty(),
                        event.position(),
                        event.security(),
                        event.calledOn(),
                        event.shortfall())));
    if (sent) {
      metrics.noticeSent();
    }
    return sent;
  }
}
