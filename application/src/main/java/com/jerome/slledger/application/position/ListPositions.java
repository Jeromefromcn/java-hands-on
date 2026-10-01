package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.PositionCursorQuery;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionSlice;
import com.jerome.slledger.application.security.Visibility;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** The position listing: a read through the projection port, never through the aggregate. */
public class ListPositions {

  private final PositionListQuery query;
  private final PositionCursorQuery cursor;

  public ListPositions(PositionListQuery query, PositionCursorQuery cursor) {
    this.query = query;
    this.cursor = cursor;
  }

  /**
   * @param visibility whose rows the caller may read; {@code requested} is narrowed to it, and the
   *     query filters on the result, so a scoped caller's page and total hold only its own rows
   * @param requested a counterparty the caller asked for, if any
   * @throws com.jerome.slledger.application.security.ForbiddenException if the caller may not read
   *     what it asked for
   *     <p>Read-only because nothing here writes. It does not make the page and its total
   *     consistent with each other: at READ COMMITTED each statement reads its own snapshot, so a
   *     position opened between the two can be counted and not listed, or the reverse.
   */
  @Transactional(readOnly = true)
  public PositionPage page(
      Visibility visibility, Optional<CounterpartyId> requested, int page, int size) {
    if (page < 0 || size < 1) {
      throw new IllegalArgumentException(
          "page must be >= 0 and size >= 1, were %d and %d".formatted(page, size));
    }
    return query.page(visibility.narrow(requested), page, size);
  }

  /**
   * The keyset alternative to {@link #page}: rows after {@code after}, with a cursor for the next
   * slice instead of a total.
   */
  @Transactional(readOnly = true)
  public PositionSlice slice(
      Visibility visibility,
      Optional<CounterpartyId> requested,
      Optional<LoanPositionId> after,
      int size) {
    if (size < 1) {
      throw new IllegalArgumentException("size must be >= 1, was " + size);
    }
    return cursor.after(visibility.narrow(requested), after, size);
  }
}
