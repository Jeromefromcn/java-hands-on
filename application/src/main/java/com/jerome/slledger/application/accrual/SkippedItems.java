package com.jerome.slledger.application.accrual;

/**
 * The record of an item a batch skipped, so that a skip is something an operator can list and not
 * only a counter on a step. Written in a transaction of its own: the chunk that skipped is rolled
 * back, and a record written inside it would go with it.
 */
public interface SkippedItems {

  /** Keyed by job, scope (the business date or snapshot) and item; a repeat replaces the reason. */
  void record(String job, String scope, String itemKey, String phase, String reason);
}
