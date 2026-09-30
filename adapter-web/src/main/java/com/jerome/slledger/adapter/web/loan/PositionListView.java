package com.jerome.slledger.adapter.web.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionSlice;
import java.util.List;

/**
 * One listing response for both ways of paging {@code GET /loans}, so the endpoint has one schema.
 * By offset: {@code page}, {@code size} and {@code totalRows}. By keyset: {@code size} and {@code
 * next}, the cursor for the following slice, absent on the last one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PositionListView(
    List<PositionView> rows, Integer page, int size, Long totalRows, String next) {

  static PositionListView of(PositionPage page) {
    return new PositionListView(
        page.rows().stream().map(PositionView::of).toList(),
        page.page(),
        page.size(),
        page.totalRows(),
        null);
  }

  static PositionListView of(PositionSlice slice, int size) {
    return new PositionListView(
        slice.rows().stream().map(PositionView::of).toList(),
        null,
        size,
        null,
        slice.next().map(id -> id.value()).orElse(null));
  }
}
