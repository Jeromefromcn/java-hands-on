package com.jerome.slledger.application.saga;

import java.util.List;
import java.util.Optional;

/**
 * What a saga has done, written ahead of doing it. Each entry commits on its own, outside the
 * transaction of the step it describes, so that a step that rolls back still leaves its entry.
 */
public interface SagaLog {

  /** The marker step: a saga's start, and its end. */
  String SAGA = "saga";

  enum Phase {
    FORWARD,
    COMPENSATE
  }

  enum Outcome {
    STARTED,
    DONE,
    FAILED
  }

  record Entry(int seq, String step, Phase phase, Outcome outcome, String detail) {}

  /**
   * @throws org.springframework.transaction.IllegalTransactionStateException if called inside a
   *     transaction: an entry that rolls back with the step it records records nothing
   */
  void record(String sagaId, String step, Phase phase, Outcome outcome, String detail);

  List<Entry> read(String sagaId);

  /** Ids of sagas that have entries and no terminal one. */
  List<String> unfinished();

  /** How a finished saga ended, or empty if it has not. */
  default Optional<Phase> terminal(String sagaId) {
    return read(sagaId).stream()
        .filter(e -> e.step().equals(SAGA) && e.outcome() == Outcome.DONE)
        .map(Entry::phase)
        .findFirst();
  }
}
