package com.jerome.slledger.application.messaging;

import com.jerome.slledger.application.event.DomainEvents;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.domain.error.DomainException;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.margin.MarginCall;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one price update from the feed, revalues the positions it touches, and records the margin
 * calls that follow.
 *
 * <p>The update is applied only if its version is higher than the stored one, so it is idempotent
 * and order-independent: a duplicate and a late arrival both change nothing. That is why a price
 * consumer needs no dedup table, and why the notice consumer, whose effect has no such natural
 * guard, does.
 *
 * <p>Everything happens in one transaction: the price, and the outbox rows for the calls. A crash
 * cannot leave a price stored whose calls were never recorded, and a redelivered update after such
 * a crash cannot be applied twice, because the transaction either committed both or neither.
 *
 * <p>The loop over positions is a single transaction and a single read of every position in the
 * security: fine for a security with hundreds of positions, not for one with a million. Stage 11's
 * batch revaluation is where that limit is dealt with.
 */
public class ApplyPriceUpdate {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");

  private final SecurityPrices prices;
  private final SecurityPositions positions;
  private final Outbox outbox;
  private final DomainEvents events;
  private final Clock clock;

  public ApplyPriceUpdate(
      SecurityPrices prices,
      SecurityPositions positions,
      Outbox outbox,
      DomainEvents events,
      Clock clock) {
    this.prices = prices;
    this.positions = positions;
    this.outbox = outbox;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public PriceApplication apply(PriceUpdated update) {
    if (!prices.applyIfNewer(update.security(), update.price(), update.version())) {
      return PriceApplication.IGNORED;
    }

    LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
    int raised = 0;
    int skipped = 0;
    for (LoanPosition position : positions.openIn(update.security())) {
      Optional<MarginCall> call;
      try {
        call = position.revalue(update.price(), today);
      } catch (DomainException cannotValue) {
        skipped++;
        continue;
      }
      if (call.isPresent()) {
        outbox.append(
            new MarginCallRaised(
                UUID.randomUUID(),
                clock.instant(),
                position.id(),
                position.counterparty(),
                update.security(),
                today,
                call.get().shortfall()));
        raised++;
      }
    }
    events.publish(new PriceApplied(update.security(), update.price(), update.version()));
    return new PriceApplication(true, raised, skipped);
  }
}
