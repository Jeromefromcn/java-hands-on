package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.BookedTrade;
import com.jerome.slledger.application.port.DuplicateRequestException;
import com.jerome.slledger.application.port.RequestFingerprint;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanTrade;
import java.util.function.Supplier;

/**
 * Open and return, answered once per idempotency key.
 *
 * <p>The key is claimed by the business write itself: the trade insert, inside the use case's
 * transaction, against {@code loan_trade}'s unique constraint. There is no separate claim that
 * could commit without the booking, or the booking without the claim.
 *
 * <p>Deliberately <strong>not</strong> transactional, like {@link ReturnPosition}. A duplicate
 * rolls its own transaction back; only then is the committed trade read, in a new one, and the
 * original result rebuilt from it:
 *
 * <ul>
 *   <li>same request: the original trade, marked replayed;
 *   <li>different request under the same key: {@link IdempotencyKeyReusedException}.
 * </ul>
 *
 * <p>The result is rebuilt from the trade row only, never from the position's current state, so a
 * replay after later returns still answers what the first call answered.
 *
 * <p>Two submissions of one key arriving together never see an "in progress" state: Postgres makes
 * the second insert wait on the unique index until the first transaction ends. If the first
 * commits, the second fails and replays; if it rolls back, the second books. The cost is that the
 * waiting request holds a connection meanwhile. See {@code docs/decisions/07-web.md}.
 *
 * <p>A submission that failed booked nothing, so its retry runs again. That is intended: an open
 * refused for credit and retried after the limit was raised should succeed.
 */
public class IdempotentBooking {

  private final OpenPosition open;
  private final ReturnPosition returns;
  private final FindBookedTrade booked;

  public IdempotentBooking(OpenPosition open, ReturnPosition returns, FindBookedTrade booked) {
    this.open = open;
    this.returns = returns;
    this.booked = booked;
  }

  public Outcome open(OpenPositionCommand command) {
    return once(command.key(), command.fingerprint(), () -> open.open(command));
  }

  public Outcome returnQuantity(ReturnCommand command) {
    return once(command.key(), command.fingerprint(), () -> returns.apply(command));
  }

  private Outcome once(IdempotencyKey key, RequestFingerprint request, Supplier<LoanTrade> book) {
    try {
      return new Outcome(book.get(), false);
    } catch (DuplicateRequestException duplicate) {
      BookedTrade earlier =
          booked
              .byKey(key)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Key %s was refused as a duplicate but no committed trade holds it"
                              .formatted(key.value()),
                          duplicate));
      if (!earlier.bookedBy(request)) {
        throw new IdempotencyKeyReusedException(key);
      }
      return new Outcome(earlier.trade(), true);
    }
  }
}
