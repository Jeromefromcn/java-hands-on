package com.jerome.slledger.domain.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.error.NotABusinessDayException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * T+2 on the Hong Kong calendar.
 *
 * <p>Every expected date below is an explicit literal. Computing the expectation from the trade
 * date would repeat whatever arithmetic the implementation uses, including its bugs.
 */
class SettlementDatesTest {

  private static final HolidayCalendar HONG_KONG = new HongKongHolidays();

  @Test
  void aMondayTradeSettlesOnWednesday() {
    assertThat(SettlementDates.settlementDate(LocalDate.of(2026, 3, 2), HONG_KONG))
        .isEqualTo(LocalDate.of(2026, 3, 4));
  }

  @Test
  void aThursdayTradeSettlesOnMondayBecauseTheWeekendIsNotCounted() {
    // Thursday 2026-03-05 -> Friday 06 (T+1) -> Monday 09 (T+2). Saturday and Sunday are not
    // business days and are not counted, rather than being counted and then shifted.
    assertThat(SettlementDates.settlementDate(LocalDate.of(2026, 3, 5), HONG_KONG))
        .isEqualTo(LocalDate.of(2026, 3, 9));
  }

  @Test
  void aTradeBeforeLunarNewYearSettlesAfterTheWholeHolidayBlock() {
    // Friday 2026-02-13 -> Monday 16 (T+1). Tue 17, Wed 18 and Thu 19 are Lunar New Year, so
    // T+2 is Friday 2026-02-20.
    assertThat(SettlementDates.settlementDate(LocalDate.of(2026, 2, 13), HONG_KONG))
        .isEqualTo(LocalDate.of(2026, 2, 20));
  }

  @Test
  void aTradeOnTheLastDayBeforeLunarNewYearSkipsFiveCalendarDaysForOneBusinessDay() {
    // Monday 2026-02-16 -> Friday 20 (T+1, after three holidays) -> Monday 23 (T+2).
    // Seven calendar days for two business days.
    assertThat(SettlementDates.settlementDate(LocalDate.of(2026, 2, 16), HONG_KONG))
        .isEqualTo(LocalDate.of(2026, 2, 23));
  }

  @Test
  void aTradeBeforeTheEasterAndChingMingBlockSettlesAfterIt() {
    // Thursday 2026-04-02 -> Wednesday 08 (T+1) -> Thursday 09 (T+2). Good Friday 03, Saturday
    // 04, Sunday 05, Easter Monday 06 (also the substituted Ching Ming) and Tuesday 07 (the
    // additional day, because the substitution landed on a holiday) are all closed. Five
    // consecutive closed days: one business day takes six calendar days to arrive.
    assertThat(SettlementDates.settlementDate(LocalDate.of(2026, 4, 2), HONG_KONG))
        .isEqualTo(LocalDate.of(2026, 4, 9));
  }

  @Test
  void aTradeCannotBeStruckOnADayTheMarketIsClosed() {
    // The alternative was to roll a weekend trade date forward to the next business day. That
    // silently rewrites a date somebody recorded, and a settlement date computed from a trade
    // date that never happened is worse than a refusal. See docs/decisions/02-domain-model.md.
    assertThatExceptionOfType(NotABusinessDayException.class)
        .isThrownBy(() -> SettlementDates.settlementDate(LocalDate.of(2026, 3, 7), HONG_KONG))
        .withMessageContaining("2026-03-07");
    assertThatExceptionOfType(NotABusinessDayException.class)
        .isThrownBy(() -> SettlementDates.settlementDate(LocalDate.of(2026, 2, 17), HONG_KONG));
  }

  @Test
  void weekendsAreNotBusinessDaysWhateverTheHolidayCalendarSays() {
    assertThat(HONG_KONG.isBusinessDay(LocalDate.of(2026, 3, 7))).isFalse();
    assertThat(HONG_KONG.isBusinessDay(LocalDate.of(2026, 3, 8))).isFalse();
    assertThat(HONG_KONG.isBusinessDay(LocalDate.of(2026, 3, 9))).isTrue();
  }

  @Test
  void aHolidayThatFallsOnASaturdayCostsNoBusinessDay() {
    // 2026-04-04, 2026-09-26 and 2026-12-26 are general holidays that land on a Saturday. They
    // are in the holiday set and they change nothing, because the day was already closed.
    assertThat(HONG_KONG.isHoliday(LocalDate.of(2026, 9, 26))).isTrue();
    // Thursday 2026-09-24 -> Friday 25 (T+1) -> Monday 28 (T+2). Saturday 26 is a general
    // holiday and costs nothing, because Saturday was already closed.
    assertThat(SettlementDates.settlementDate(LocalDate.of(2026, 9, 24), HONG_KONG))
        .isEqualTo(LocalDate.of(2026, 9, 28));
  }

  @Test
  void aCalendarWithNoBusinessDaysAtAllIsRefusedRatherThanLoopedOn() {
    // HolidayCalendar is an extension point: stage 6 replaces HongKongHolidays with something
    // loaded from a real source, and a misconfigured one that closes every day would otherwise
    // spin here forever holding whatever thread called it.
    // The trade date itself has to be open, or the refusal would be the ordinary
    // NotABusinessDayException and this test would pass without exercising the loop at all.
    LocalDate tradeDate = LocalDate.of(2026, 3, 2);
    HolidayCalendar closedFromTomorrow = date -> date.isAfter(tradeDate);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> SettlementDates.settlementDate(tradeDate, closedFromTomorrow))
        .withMessageContaining("business day");
  }

  @Test
  void theHolidaySetCoversExactlyTheSeventeenGeneralHolidaysOfTwentyTwentySix() {
    assertThat(new HongKongHolidays().dates()).hasSize(17);
  }
}
