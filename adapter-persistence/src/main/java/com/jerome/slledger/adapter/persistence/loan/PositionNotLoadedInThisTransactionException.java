package com.jerome.slledger.adapter.persistence.loan;

/**
 * A position was handed back for writing that this transaction never loaded, although a row with
 * its id exists.
 *
 * <p>Either it was loaded in an earlier transaction, in which case its state may be stale and
 * writing it would silently undo whatever committed in between, or it was built fresh with an id
 * already taken. Both are refused. The version check cannot catch the first case: a save that
 * re-reads the row compares the version it just read with itself. See {@code
 * docs/decisions/06-persistence.md}.
 *
 * <p>A {@code RuntimeException}, not an {@code IllegalStateException}, although the latter reads
 * more naturally. The adapters are {@code @Repository} beans, and Spring's persistence exception
 * translation turns any {@code IllegalStateException} leaving one into an {@code
 * InvalidDataAccessApiUsageException}: this type would never reach a caller under its own name.
 */
public class PositionNotLoadedInThisTransactionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PositionNotLoadedInThisTransactionException(String positionId) {
    super(
        "Position %s was not loaded in this transaction; load it again and reapply the change"
            .formatted(positionId));
  }
}
