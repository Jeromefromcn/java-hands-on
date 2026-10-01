package com.jerome.slledger.application.audit;

/** Where audit lines are written. Joins whatever transaction the calling thread is in. */
public interface AuditStore {

  /** Records nothing: for the use-case tests that are not about the trail. */
  AuditStore NONE = event -> {};

  void append(AuditEvent event);
}
