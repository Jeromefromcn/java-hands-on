package com.jerome.slledger.domain.loan;

import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import com.jerome.slledger.domain.party.ExposureSummary;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Summaries over a collection of positions.
 *
 * <p>In {@code loan} rather than in {@code party}, although what it produces is a statement about
 * counterparties, because it reads {@link LoanPosition#counterparty()} and {@link
 * LoanPosition#collateral()} and both are package-private. Moving this class to {@code party} would
 * mean widening those to public so that one summary can read them, which trades a tight aggregate
 * for a tidy package name. The aggregate wins.
 */
public final class LoanPositions {

  private LoanPositions() {}

  /**
   * Counts and totals the collateral of the positions given, grouped by counterparty.
   *
   * <p>It summarises exactly the list it receives. Which positions belong in an exposure figure —
   * closed ones, ones that have not settled yet — is a question about what the caller is asking,
   * not about arithmetic, and answering it here would hide the answer from the caller.
   *
   * <p>The currency is a parameter because the reduction needs an identity and {@link Money#zero}
   * needs a currency to make one. That is not an inconvenience to route around: it is why a
   * position collateralised in another currency raises {@code CurrencyMismatchException} here
   * instead of being quietly added in.
   */
  public static Map<CounterpartyId, ExposureSummary> exposureByCounterparty(
      List<LoanPosition> positions, Currency currency) {
    Objects.requireNonNull(positions, "positions");
    Objects.requireNonNull(currency, "currency");

    return positions.stream()
        .collect(
            Collectors.groupingBy(
                LoanPosition::counterparty,
                Collectors.teeing(
                    Collectors.counting(),
                    Collectors.reducing(
                        Money.zero(currency), LoanPosition::collateral, Money::plus),
                    ExposureSummary::new)));
  }
}
