Feature: Loan repayment reduces outstanding balance

  Scenario: Partial return after a multi-day loan
    Given a counterparty borrows 1000 shares
    And 3 days pass
    When the counterparty returns 400 shares
    Then the outstanding balance is 600 shares
    And accrual has been booked for 3 days
