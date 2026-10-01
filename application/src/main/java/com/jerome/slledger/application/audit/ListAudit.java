package com.jerome.slledger.application.audit;

import com.jerome.slledger.application.position.InvalidQueryException;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** The audit trail, newest first, capped: an audit read is not an export. */
public class ListAudit {

  public static final int MAX_LIMIT = 200;

  private final AuditQuery query;

  public ListAudit(AuditQuery query) {
    this.query = query;
  }

  /**
   * @throws InvalidQueryException if {@code limit} is not between 1 and {@link #MAX_LIMIT}
   */
  @Transactional(readOnly = true)
  public List<AuditEvent> recent(Optional<String> subjectId, int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidQueryException("limit must be between 1 and " + MAX_LIMIT);
    }
    return query.recent(subjectId, limit);
  }
}
