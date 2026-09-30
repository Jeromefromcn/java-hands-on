package com.jerome.slledger.application.accrual;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Books a chunk of accrual entries in the caller's transaction. A chunk is one transaction: the
 * entries and the position of the reader that produced them commit together, so a restart resumes
 * exactly after the last committed entry.
 */
public class BookAccruals {

  private final AccrualLedger ledger;

  public BookAccruals(AccrualLedger ledger) {
    this.ledger = ledger;
  }

  @Transactional
  public BookResult book(List<AccrualEntry> entries) {
    return ledger.book(entries);
  }
}
