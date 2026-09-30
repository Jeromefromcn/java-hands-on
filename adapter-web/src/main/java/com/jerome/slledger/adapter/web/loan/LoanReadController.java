package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.application.position.InvalidQueryException;
import com.jerome.slledger.application.position.ListAccruals;
import com.jerome.slledger.application.position.ListPositions;
import com.jerome.slledger.application.position.ShowPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Optional;
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

  /**
   * The listing, by offset ({@code page}, with the total) or by keyset ({@code after}, with a
   * cursor for the next slice; {@code after=} with no value starts from the first row). Keyset is
   * the one that stays fast on deep pages and stable under concurrent inserts; see {@code
   * docs/decisions/07-web.md}. Asking for both is a {@code 400}.
   */
  @GetMapping
  public PositionListView list(
      @RequestParam(required = false) @Min(0) Integer page,
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    if (after != null) {
      if (page != null) {
        throw new InvalidQueryException("page and after are two ways of paging; send one");
      }
      // An empty after= starts a keyset walk from the first row.
      Optional<LoanPositionId> cursor =
          after.isEmpty() ? Optional.empty() : Optional.of(new LoanPositionId(after));
      return PositionListView.of(list.slice(cursor, size), size);
    }
    return PositionListView.of(list.page(page == null ? 0 : page, size));
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
