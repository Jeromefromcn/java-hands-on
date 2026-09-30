package com.jerome.slledger.application.reconciliation;

/** A line of the custodian's file that is not a valid holding. Skippable: it concerns one line. */
public class MalformedHoldingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String positionId;
  private final int line;

  /**
   * @param positionId the position the line names, if it names one that could be read
   */
  public MalformedHoldingException(String positionId, int line, String reason) {
    super("Line %d of the custodian's file is not a valid holding: %s".formatted(line, reason));
    this.positionId = positionId;
    this.line = line;
  }

  /** The position the line names, or {@code null} when the id itself was unreadable. */
  public String positionId() {
    return positionId;
  }

  public int line() {
    return line;
  }

  /** The key a skip record is filed under: the position if known, else the line. */
  public String itemKey() {
    return positionId != null ? positionId : "line:" + line;
  }
}
