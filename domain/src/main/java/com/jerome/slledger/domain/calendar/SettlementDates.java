package com.jerome.slledger.domain.calendar;

import com.jerome.slledger.domain.error.NotABusinessDayException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Settlement date arithmetic.
 *
 * <p>Everything here is {@link LocalDate}. A trade date and a settlement date are business dates:
 * they have no time of day, no offset and no zone, and giving them one would invite a conversion
 * that could move them by a day. {@code Instant} belongs on events, which record when something
 * happened on a machine; {@code ZonedDateTime} belongs in the web adapter, where a time is being
 * shown to a person. Neither appears in this package. See {@code
 * docs/decisions/02-domain-model.md}.
 */
public final class SettlementDates {

  /**
   * T+2. The convention of most equity markets since 2017, and of Hong Kong. It is a constant
   * rather than a parameter because this ledger settles one way; the day it settles two ways, this
   * becomes a property of the trade and not of this class.
   */
  private static final int SETTLEMENT_LAG_IN_BUSINESS_DAYS = 2;

  private SettlementDates() {}

  /**
   * The date a trade struck on {@code tradeDate} settles.
   *
   * <p>A trade date that is not a business day is refused rather than rolled forward.
   */
  public static LocalDate settlementDate(LocalDate tradeDate, HolidayCalendar calendar) {
    Objects.requireNonNull(tradeDate, "tradeDate");
    Objects.requireNonNull(calendar, "calendar");
    if (!calendar.isBusinessDay(tradeDate)) {
      throw new NotABusinessDayException(tradeDate);
    }

    LocalDate date = tradeDate;
    for (int counted = 0; counted < SETTLEMENT_LAG_IN_BUSINESS_DAYS; ) {
      date = date.plusDays(1);
      if (calendar.isBusinessDay(date)) {
        counted++;
      }
    }
    return date;
  }
}
