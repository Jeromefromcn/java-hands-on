package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.application.port.PositionPage;
import java.util.List;

/** An offset page and the total it is cut from. */
public record PositionPageView(List<PositionView> rows, int page, int size, long totalRows) {

  static PositionPageView of(PositionPage page) {
    return new PositionPageView(
        page.rows().stream().map(PositionView::of).toList(),
        page.page(),
        page.size(),
        page.totalRows());
  }
}
