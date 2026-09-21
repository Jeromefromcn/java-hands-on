package com.jerome.slledger.domain.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Which days a market is open.
 *
 * <p>Weekends are handled here and holidays are delegated, because the weekend rule is the same for
 * every market this ledger will ever see and the holiday list is not. An implementation answers one
 * question.
 */
public interface HolidayCalendar {

  boolean isHoliday(LocalDate date);

  default boolean isBusinessDay(LocalDate date) {
    return date.getDayOfWeek() != DayOfWeek.SATURDAY
        && date.getDayOfWeek() != DayOfWeek.SUNDAY
        && !isHoliday(date);
  }
}
