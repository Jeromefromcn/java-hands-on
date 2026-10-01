package com.jerome.slledger.application.position;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.port.AccrualEntryQuery;
import com.jerome.slledger.application.port.PositionCursorQuery;
import com.jerome.slledger.application.port.PositionDetailQuery;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.application.port.PositionSlice;
import com.jerome.slledger.application.security.ForbiddenException;
import com.jerome.slledger.application.security.Visibility;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The read use cases take a Visibility and apply it: a row outside it is not found, and a query is
 * asked for the narrowed counterparty and never for more.
 */
class VisibilityUseCaseTest {

  private static final CounterpartyId A = new CounterpartyId("CP-001");
  private static final CounterpartyId B = new CounterpartyId("CP-002");
  private static final LoanPositionId OF_A = new LoanPositionId("LP-A");
  private static final LoanPositionId OF_B = new LoanPositionId("LP-B");
  private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

  private static PositionRow row(LoanPositionId id, CounterpartyId owner) {
    return new PositionRow(
        id,
        owner,
        new Isin("HK0000000007"),
        LoanStatus.OPEN,
        100,
        100,
        Money.of("10.00", Currency.getInstance("HKD")));
  }

  private static final PositionDetailQuery DETAILS =
      id ->
          id.equals(OF_A)
              ? Optional.of(row(OF_A, A))
              : id.equals(OF_B) ? Optional.of(row(OF_B, B)) : Optional.empty();

  @Test
  @DisplayName("show: a foreign position and a missing one are the same exception")
  void showForeignIsMissing() {
    ShowPosition show = new ShowPosition(DETAILS);

    assertThat(show.show(Visibility.only(A), OF_A).counterparty()).isEqualTo(A);
    assertThatThrownBy(() -> show.show(Visibility.only(A), OF_B))
        .isInstanceOf(UnknownReferenceException.class)
        .hasMessage(catchMessage(() -> show.show(Visibility.only(A), new LoanPositionId("LP-B"))));
    assertThatThrownBy(() -> show.show(Visibility.only(A), new LoanPositionId("LP-NONE")))
        .isInstanceOf(UnknownReferenceException.class);
    assertThat(show.show(Visibility.ALL, OF_B).counterparty()).isEqualTo(B);
    assertThatThrownBy(() -> show.show(Visibility.NOBODY, OF_A))
        .isInstanceOf(UnknownReferenceException.class);
  }

  private static String catchMessage(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      return e.getMessage();
    }
    throw new AssertionError("expected an exception");
  }

  @Test
  @DisplayName("accruals: a foreign position is not found, even for a valid range")
  void accrualsForeignIsMissing() {
    List<String> asked = new ArrayList<>();
    AccrualEntryQuery entries =
        (position, from, to) -> {
          asked.add(position.value());
          return List.of();
        };
    ListAccruals accruals = new ListAccruals(DETAILS, entries);

    assertThat(accruals.between(Visibility.only(A), OF_A, DAY, DAY)).isEmpty();
    assertThatThrownBy(() -> accruals.between(Visibility.only(A), OF_B, DAY, DAY))
        .isInstanceOf(UnknownReferenceException.class);
    assertThat(asked).containsExactly("LP-A");
  }

  @Test
  @DisplayName("list: the query is asked for the narrowed counterparty, by page and by keyset")
  void listNarrows() {
    List<Optional<CounterpartyId>> asked = new ArrayList<>();
    PositionListQuery page =
        (owner, p, size) -> {
          asked.add(owner);
          return new PositionPage(List.of(), p, size, 0);
        };
    PositionCursorQuery cursor =
        (owner, after, size) -> {
          asked.add(owner);
          return new PositionSlice(List.of(), Optional.empty());
        };
    ListPositions list = new ListPositions(page, cursor);

    list.page(Visibility.only(A), Optional.empty(), 0, 10);
    list.page(Visibility.only(A), Optional.of(A), 0, 10);
    list.slice(Visibility.only(A), Optional.empty(), Optional.empty(), 10);
    list.page(Visibility.ALL, Optional.empty(), 0, 10);
    list.page(Visibility.ALL, Optional.of(B), 0, 10);

    assertThat(asked)
        .containsExactly(
            Optional.of(A), Optional.of(A), Optional.of(A), Optional.empty(), Optional.of(B));
  }

  @Test
  @DisplayName("list: asking for someone else's, or having no scope at all, never reaches a query")
  void listRefuses() {
    List<Object> reached = new ArrayList<>();
    ListPositions list =
        new ListPositions(
            (owner, p, size) -> {
              reached.add(owner);
              return new PositionPage(List.of(), p, size, 0);
            },
            (owner, after, size) -> {
              reached.add(owner);
              return new PositionSlice(List.of(), Optional.empty());
            });

    assertThatThrownBy(() -> list.page(Visibility.only(A), Optional.of(B), 0, 10))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> list.slice(Visibility.only(A), Optional.of(B), Optional.empty(), 10))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> list.page(Visibility.NOBODY, Optional.empty(), 0, 10))
        .isInstanceOf(ForbiddenException.class);
    assertThat(reached).isEmpty();
  }
}
