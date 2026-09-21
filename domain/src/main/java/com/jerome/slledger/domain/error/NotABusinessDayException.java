package com.jerome.slledger.domain.error;

import java.time.LocalDate;

/**
 * A business date was offered on a day the market was closed.
 *
 * <p>The alternative — rolling the date forward to the next business day — silently rewrites a date
 * somebody recorded. A settlement date computed from a trade date that never happened is worse than
 * a refusal, because it reconciles.
 */
public final class NotABusinessDayException extends DomainException {

  private static final long serialVersionUID = 1L;

  public NotABusinessDayException(LocalDate date) {
    super("%s is not a business day".formatted(date));
  }
}
