package com.jerome.slledger.application.accrual;

/** How a batch's keyspace is cut into slices that run side by side. */
public enum PartitionScheme {

  /** One slice: the whole keyspace, one thread. */
  NONE,

  /**
   * One slice per counterparty. The natural business cut, and the roadmap's; it is only as even as
   * the counterparties are, and one large one makes one slow slice.
   */
  COUNTERPARTY,

  /** Slices of equal row count over the id order, whatever the counterparties look like. */
  BALANCED
}
