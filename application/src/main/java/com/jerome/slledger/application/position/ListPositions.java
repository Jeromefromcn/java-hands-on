package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import org.springframework.transaction.annotation.Transactional;

/** The position listing: a read through the projection port, never through the aggregate. */
public class ListPositions {

  private final PositionListQuery query;

  public ListPositions(PositionListQuery query) {
    this.query = query;
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
}
