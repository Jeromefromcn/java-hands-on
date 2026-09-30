package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.application.position.ListAccruals;
import com.jerome.slledger.application.position.ListPositions;
import com.jerome.slledger.application.position.ShowPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The read side of {@code /loans}: the listing, one position, and its accrual entries. */
@RestController
@RequestMapping("/loans")
public class LoanReadController {

  static final int MAX_PAGE_SIZE = 200;

  private final ListPositions list;
  private final ShowPosition show;
  private final ListAccruals accruals;

  LoanReadController(ListPositions list, ShowPosition show, ListAccruals accruals) {
    this.list = list;
    this.show = show;
    this.accruals = accruals;
  }

  /** Offset paging, with the total. */
  @GetMapping
  public PositionPageView page(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return PositionPageView.of(list.page(page, size));
  }

  @GetMapping("/{id}")
  public PositionView show(@PathVariable String id) {
    return PositionView.of(show.show(new LoanPositionId(id)));
  }

  @GetMapping("/{id}/accruals")
  public AccrualsView accruals(
      @PathVariable String id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return AccrualsView.of(id, from, to, accruals.between(new LoanPositionId(id), from, to));
  }
}
