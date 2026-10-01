package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.AccrualEntryQuery;
import com.jerome.slledger.application.port.PositionDetailQuery;
import com.jerome.slledger.application.security.Visibility;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.loan.LoanPositionId;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * A position's accrual entries over a date range.
 *
 * <p>Not paged: a position books at most one entry per day, so capping the range at {@link
 * #MAX_DAYS} days bounds the answer as well as a page size would, and a range is what a reader of
 * fees asks for. See {@code docs/decisions/07-web.md}.
 */
public class ListAccruals {

  /** A year, including a leap day. */
  public static final int MAX_DAYS = 366;

  private final PositionDetailQuery positions;
  private final AccrualEntryQuery entries;

  public ListAccruals(PositionDetailQuery positions, AccrualEntryQuery entries) {
    this.positions = positions;
    this.entries = entries;
  }

  /**
   * @throws InvalidQueryException if {@code from} is after {@code to}, or the range, both ends
   *     included, is longer than {@link #MAX_DAYS} days
   * @throws UnknownReferenceException if there is no such position, or it is not one the caller may
   *     see (the same answer for both): an unknown position is not the same answer as a position
   *     with no entries
   */
  @Transactional(readOnly = true)
  public List<AccrualEntry> between(
      Visibility visibility, LoanPositionId position, LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      throw new InvalidQueryException("from %s is after to %s".formatted(from, to));
    }
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    if (days > MAX_DAYS) {
      throw new InvalidQueryException(
          "the range covers %d days; at most %d are answered at once".formatted(days, MAX_DAYS));
    }
    if (positions.find(position).filter(row -> visibility.permits(row.counterparty())).isEmpty()) {
      throw UnknownReferenceException.subject("position", position.value());
    }
    return entries.of(position, from, to);
  }
}
