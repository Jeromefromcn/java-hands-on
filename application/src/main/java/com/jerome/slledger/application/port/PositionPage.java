package com.jerome.slledger.application.port;

import java.util.List;

/** One page of positions, ordered by id, and the total the pages are cut from. */
public record PositionPage(List<PositionRow> rows, int page, int size, long totalRows) {

  public PositionPage {
    rows = List.copyOf(rows);
  }
}
