package com.jerome.slledger.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.event.LoanEvent.Accrued;
import com.jerome.slledger.domain.event.LoanEvent.MarginCalled;
import com.jerome.slledger.domain.event.LoanEvent.Opened;
import com.jerome.slledger.domain.event.LoanEvent.Returned;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sealed hierarchy and the exhaustiveness check it buys.
 *
 * <p>The switch below has <strong>no default branch</strong>. That omission is the whole point: a
 * default is a catch-all that always matches, so adding a fifth event would compile and fall into
 * it silently. Without one, the compiler has to prove every permitted subtype is handled, and a
 * fifth event becomes a build failure at every site that switches on LoanEvent. The compiler's
 * message on a deliberately removed case is in {@code docs/evidence/02-sealed-exhaustive.txt}.
 */
class LoanEventTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final LoanPositionId POSITION = new LoanPositionId("LP-0001");
  private static final Instant AT = Instant.parse("2026-03-02T01:30:00Z");

  @Test
  void everyEventIsHandledWithoutADefaultBranch() {
    List<LoanEvent> history =
        List.of(
            new Opened(
                POSITION,
                AT,
                new CounterpartyId("CP-0001"),
                new Isin("HK0000000001"),
                10_000L,
                Money.of("4126000.00", HKD)),
            new Returned(POSITION, AT, 4_000L, 6_000L),
            new Accrued(POSITION, AT, LocalDate.of(2026, 3, 2), Money.of("100.00", HKD)),
            new MarginCalled(POSITION, AT, Money.of("100.00", HKD)));

    assertThat(history.stream().map(LoanEventTest::describe))
        .containsExactly(
            "opened 10000",
            "returned 4000, 6000 left",
            "accrued 100.00 for 2026-03-02",
            "called for 100.00");
  }

  @Test
  void everyEventCarriesItsPositionAndTheInstantItHappened() {
    LoanEvent event = new Returned(POSITION, AT, 4_000L, 6_000L);

    assertThat(event.position()).isEqualTo(POSITION);
    assertThat(event.occurredAt()).isEqualTo(AT);
  }

  @Test
  void anAccrualCarriesBothAMachineInstantAndABusinessDate() {
    // The two time types on one type, which is where the distinction is easiest to see. The
    // batch ran at 01:30 UTC; the day it was accruing for is 2 March on the Hong Kong calendar.
    // Collapsing these into one field means the accrual date moves when the batch is rescheduled.
    Accrued accrued = new Accrued(POSITION, AT, LocalDate.of(2026, 3, 2), Money.of("100.00", HKD));

    assertThat(accrued.occurredAt()).isEqualTo(Instant.parse("2026-03-02T01:30:00Z"));
    assertThat(accrued.accrualDate()).isEqualTo(LocalDate.of(2026, 3, 2));
  }

  /** No default. Removing any one case below stops this file compiling. */
  private static String describe(LoanEvent event) {
    return switch (event) {
      case Opened opened -> "opened " + opened.quantity();
      case Returned returned ->
          "returned %d, %d left".formatted(returned.quantity(), returned.outstandingAfter());
      case Accrued accrued ->
          "accrued %s for %s"
              .formatted(accrued.fee().amount().toPlainString(), accrued.accrualDate());
      case MarginCalled called -> "called for " + called.shortfall().amount().toPlainString();
    };
  }
}
