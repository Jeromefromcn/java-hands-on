package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.PositionCursorQuery;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionSlice;
import com.jerome.slledger.domain.loan.LoanPositionId;
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
   * Read-only because nothing here writes. It does not make the page and its total consistent with
   * each other: at READ COMMITTED each statement reads its own snapshot, so a position opened
   * between the two can be counted and not listed, or the reverse.
   */
  @Transactional(readOnly = true)
  public PositionPage page(int page, int size) {
    if (page < 0 || size < 1) {
      throw new IllegalArgumentException(
          "page must be >= 0 and size >= 1, were %d and %d".formatted(page, size));
    }
    return query.page(page, size);
  }

  /**
   * The keyset alternative to {@link #page}: rows after {@code after}, with a cursor for the next
   * slice instead of a total.
   */
  @Transactional(readOnly = true)
  public PositionSlice slice(Optional<LoanPositionId> after, int size) {
    if (size < 1) {
      throw new IllegalArgumentException("size must be >= 1, was " + size);
    }
    return cursor.after(after, size);
  }
}
