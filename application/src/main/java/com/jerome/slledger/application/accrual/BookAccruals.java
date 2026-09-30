package com.jerome.slledger.application.accrual;

import com.jerome.slledger.application.cache.WriteFence;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Books a chunk of accrual entries in the caller's transaction. A chunk is one transaction: the
 * entries and the position of the reader that produced them commit together, so a restart resumes
 * exactly after the last committed entry.
 *
 * <p>When the run holds a lease, the chunk also checks the lease's fencing token first, in the same
 * transaction: a holder that was paused past its lease and resumed is refused before it writes, and
 * its chunk rolls back.
 */
public class BookAccruals {

  /** The resource a run's writes are fenced under, and the token its lease carried. */
  public record Fenced(String resource, long token) {}

  private final AccrualLedger ledger;
  private final WriteFence fence;

  public BookAccruals(AccrualLedger ledger, WriteFence fence) {
    this.ledger = ledger;
    this.fence = fence;
  }

  @Transactional
  public BookResult book(List<AccrualEntry> entries, Optional<Fenced> fenced) {
    fenced.ifPresent(f -> fence.verify(f.resource(), f.token()));
    return ledger.book(entries);
  }

  /**
   * Raises the fence to this run's token before its first write. Committed on its own, so the claim
   * is visible to every other instance at once, and refused if a later owner has already claimed.
   */
  @Transactional
  public void claim(Fenced fenced) {
    fence.admit(fenced.resource(), fenced.token());
  }
}
