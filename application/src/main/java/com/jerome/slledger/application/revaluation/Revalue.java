package com.jerome.slledger.application.revaluation;

import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.accrual.CorruptPositionException;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.domain.margin.MarginCall;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * One position marked to a price: a margin call if its collateral no longer covers it, nothing if
 * it does. The same domain rule the inline path applies ({@code LoanPosition.revalue}); only the
 * loop around it differs.
 */
public final class Revalue {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");

  private final Clock clock;

  public Revalue(Clock clock) {
    this.clock = clock;
  }

  /**
   * @return the call to raise, or empty when the position is covered
   * @throws CorruptPositionException the row could not be restored
   * @throws com.jerome.slledger.domain.error.DomainException collateral in another currency than
   *     the price: there is no rate to compare them with
   */
  public Optional<MarginCallRaised> revalue(AccrualCandidate candidate) {
    if (candidate instanceof AccrualCandidate.Corrupt corrupt) {
      throw new CorruptPositionException(corrupt.positionId(), corrupt.reason());
    }
    AccrualCandidate.Valid valid = (AccrualCandidate.Valid) candidate;
    LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
    Optional<MarginCall> call = valid.position().revalue(valid.unitPrice(), today);
    return call.map(
        c ->
            new MarginCallRaised(
                UUID.randomUUID(),
                clock.instant(),
                valid.position().id(),
                valid.position().counterparty(),
                valid.position().security(),
                today,
                c.shortfall()));
  }
}
