package com.jerome.slledger.application.accrual;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The positions a business date's accrual covers, in pages, in id order.
 *
 * <p>Active on a date: not closed, and its opening trade settles on or before the date. A position
 * is read as it is now: this ledger keeps no history of quantities, so a run for a past date uses
 * today's outstanding quantity and price (the decision record says where that breaks).
 */
public interface AccrualCandidates {

  /**
   * Up to {@code limit} candidates in {@code slice} with an id greater than {@code afterId}, in id
   * order. Keyset, never offset: the cost of a page does not grow with its position in the scan,
   * and a restart is a seek to the last key.
   */
  List<AccrualCandidate> page(
      LocalDate businessDate, Slice slice, Optional<String> afterId, int limit);

  /**
   * Disjoint slices that cover every active position on the date. {@code parts} is the target for
   * {@link PartitionScheme#BALANCED} and ignored by the other schemes.
   */
  List<Slice> slices(PartitionScheme scheme, LocalDate businessDate, int parts);
}
