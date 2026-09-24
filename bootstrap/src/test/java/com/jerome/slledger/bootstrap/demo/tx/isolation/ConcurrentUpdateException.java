package com.jerome.slledger.bootstrap.demo.tx.isolation;

import java.util.ArrayList;
import java.util.List;

/** Every attempt at a return lost to a concurrent writer. Carries what each attempt saw. */
public class ConcurrentUpdateException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ArrayList<String> attempts;

  public ConcurrentUpdateException(String id, List<String> attempts) {
    super("position " + id + " changed under every attempt: " + attempts);
    this.attempts = new ArrayList<>(attempts);
  }

  /** Per attempt: {@code "0 rows"} for a version mismatch, otherwise the failing SQLState. */
  public List<String> attempts() {
    return List.copyOf(attempts);
  }
}
