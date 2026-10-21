package com.jerome.slledger.application.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of the audit trail: who did what to which subject, and how it ended. It carries ids and
 * codes and never an amount or a counterparty's name: the amount is on the trade the subject points
 * to, so the trail cannot disagree with the ledger and is not a second copy of what the money
 * invariant protects.
 *
 * @param reason a machine code for a refusal (an exception's simple name, a rule's code), empty
 *     when booked, the step ("change 7: 0->5") for a rollout change; never a message, which can
 *     quote an amount
 */
public record AuditEvent(
    Instant at,
    String actor,
    Action action,
    Subject subject,
    String subjectId,
    Result result,
    String reason) {

  public enum Action {
    OPEN,
    RETURN,
    /** A request refused before it reached a use case. */
    ACCESS,
    /** Stage 14: a change to a rollout, or a refused attempt at one. */
    ROLLOUT
  }

  public enum Subject {
    /** A position that exists: the subject of a booking. */
    POSITION,
    /** A booking request that booked nothing; the id is its idempotency key. */
    REQUEST,
    /** An endpoint a caller was refused; the id is the method and path. */
    ENDPOINT,
    /** A rollout; the id is the flag's name. */
    FLAG
  }

  public enum Result {
    BOOKED,
    REJECTED,
    DENIED,
    /** Stage 14: a raise proposed (maker), approved (checker), or a rollout lowered. */
    PROPOSED,
    APPROVED,
    LOWERED
  }

  public AuditEvent {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(result, "result");
    reason = reason == null ? "" : reason;
  }
}
