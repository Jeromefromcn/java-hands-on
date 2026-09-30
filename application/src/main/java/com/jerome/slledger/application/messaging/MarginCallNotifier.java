package com.jerome.slledger.application.messaging;

import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.NoticeChannel.MarginCallNotice;

/**
 * Tells a counterparty about a margin call, once per event. The event's id is the dedup key, so the
 * same message delivered again (a relay resend, a rebalance, a replayed offset) sends nothing.
 */
public class MarginCallNotifier {

  public static final String CONSUMER = "margin-call-notifier";

  private final IdempotentConsumer guard;
  private final NoticeChannel channel;

  public MarginCallNotifier(IdempotentConsumer guard, NoticeChannel channel) {
    this.guard = guard;
    this.channel = channel;
  }

  /**
   * @return {@code true} if a notice was sent, {@code false} if this event had already been handled
   */
  public boolean handle(MarginCallRaised event) {
    return guard.once(
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
  }
}
