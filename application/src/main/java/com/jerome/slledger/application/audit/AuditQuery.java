package com.jerome.slledger.application.audit;

import java.util.List;
import java.util.Optional;

/** The audit trail as a read model, newest first. */
public interface AuditQuery {

  /** Up to {@code limit} lines, of one subject if given, newest first. */
  List<AuditEvent> recent(Optional<String> subjectId, int limit);
}
