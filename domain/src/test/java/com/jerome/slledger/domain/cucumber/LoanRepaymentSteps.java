package com.jerome.slledger.domain.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Step definitions for {@code loan-repayment.feature}. Every step calls the same {@link
 * LoanPosition} and {@link AccrualEntry} types the equivalent {@code @Test} methods in {@code
 * LoanPositionTest} and {@code AccrualEntryTest} already call — no domain API exists for Cucumber's
 * sake.
 *
 * <p>State lives in instance fields, one instance per scenario, which is Cucumber's own per-
 * scenario object lifecycle.
 */
public class LoanRepaymentSteps {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Money OUTSTANDING_VALUE = Money.of("1000000.00", HKD);
  private static final AccrualRule RULE = new FlatRate(Rate.of("0.0365"));

  private LoanPosition position;
  private final List<AccrualEntry> accrualEntries = new ArrayList<>();
  private LocalDate day = LocalDate.of(2026, 3, 2);

  @Given("a counterparty borrows {int} shares")
  public void aCounterpartyBorrowsShares(long quantity) {
    position =
        LoanPosition.open(
            new LoanPositionId("LP-CUCUMBER-0001"),
            new CounterpartyId("CP-0001"),
            new Isin("HK0000000001"),
            quantity,
            OUTSTANDING_VALUE,
            RULE);
  }

  @Given("{int} days pass")
  public void daysPass(int dayCount) {
    for (int i = 0; i < dayCount; i++) {
      accrualEntries.add(AccrualEntry.accrue(position.id(), OUTSTANDING_VALUE, RULE, day));
      day = day.plusDays(1);
    }
  }

  @When("the counterparty returns {int} shares")
  public void theCounterpartyReturnsShares(long quantity) {
    position.returnQuantity(quantity);
  }

  @Then("the outstanding balance is {int} shares")
  public void theOutstandingBalanceIsShares(long expectedOutstanding) {
    assertThat(position.outstandingQuantity()).isEqualTo(expectedOutstanding);
  }

  @Then("accrual has been booked for {int} days")
  public void accrualHasBeenBookedForDays(int expectedDayCount) {
    assertThat(accrualEntries).hasSize(expectedDayCount);
  }
}
