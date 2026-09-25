package com.jerome.slledger.domain.calendar;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;

/**
 * The seventeen Hong Kong general holidays of 2026.
 *
 * <p><strong>Hand-entered, covering 2026 only, and not authoritative.</strong> These dates were
 * transcribed from the publicly gazetted general holiday list. They are not a market-data feed,
 * they are not maintained, and they carry no guarantee: a settlement date computed for any other
 * year is computed against an empty holiday set and will be wrong. Replacing this class with a
 * calendar loaded from a real source is not scheduled in any stage yet; nothing outside this file
 * needs to change when it happens, which is the reason {@link HolidayCalendar} is an interface.
 *
 * <p>Note that three of the seventeen — 4 April, 26 September and 26 December — fall on a Saturday.
 * They are listed for completeness and they cost no business day, because Saturday was already
 * closed. A holiday list is not the same thing as a list of days the market loses.
 */
public final class HongKongHolidays implements HolidayCalendar {

  private static final Set<LocalDate> DATES =
      Set.of(
          LocalDate.of(2026, 1, 1), // The first day of January
          LocalDate.of(2026, 2, 17), // Lunar New Year's Day
          LocalDate.of(2026, 2, 18), // The second day of Lunar New Year
          LocalDate.of(2026, 2, 19), // The third day of Lunar New Year
          LocalDate.of(2026, 4, 3), // Good Friday
          LocalDate.of(2026, 4, 4), // The day following Good Friday (a Saturday)
          LocalDate.of(2026, 4, 6), // The day following Ching Ming Festival
          LocalDate.of(2026, 4, 7), // The day following Easter Monday
          LocalDate.of(2026, 5, 1), // Labour Day
          LocalDate.of(2026, 5, 25), // The day following the Birthday of the Buddha
          LocalDate.of(2026, 6, 19), // Tuen Ng Festival
          LocalDate.of(2026, 7, 1), // HKSAR Establishment Day
          LocalDate.of(2026, 9, 26), // The day following Mid-Autumn Festival (a Saturday)
          LocalDate.of(2026, 10, 1), // National Day
          LocalDate.of(2026, 10, 19), // The day following Chung Yeung Festival
          LocalDate.of(2026, 12, 25), // Christmas Day
          LocalDate.of(2026, 12, 26)); // The first weekday after Christmas Day (a Saturday)

  @Override
  public boolean isHoliday(LocalDate date) {
    return DATES.contains(date);
  }

  /** The set itself, so that a test can assert its size rather than trust this javadoc. */
  public Set<LocalDate> dates() {
    return Collections.unmodifiableSet(DATES);
  }
}
