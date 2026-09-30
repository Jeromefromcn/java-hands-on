package com.jerome.slledger.application.accrual;

/** What booking a batch of entries did: rows inserted, and rows the store already had. */
public record BookResult(int inserted, int alreadyPresent) {

  public BookResult plus(BookResult other) {
    return new BookResult(inserted + other.inserted, alreadyPresent + other.alreadyPresent);
  }
}
