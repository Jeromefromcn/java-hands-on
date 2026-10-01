package com.jerome.slledger.adapter.web.audit;

import com.jerome.slledger.adapter.web.security.Access;
import com.jerome.slledger.application.audit.AuditEvent;
import com.jerome.slledger.application.audit.ListAudit;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit trail, for the auditor role only: newest first, one subject if named. */
@RestController
@RequestMapping("/audit")
public class AuditController {

  private final ListAudit audit;

  AuditController(ListAudit audit) {
    this.audit = audit;
  }

  public record AuditView(List<Line> events) {}

  public record Line(
      Instant at,
      String actor,
      String action,
      String subject,
      String subjectId,
      String result,
      String reason) {
    static Line of(AuditEvent event) {
      return new Line(
          event.at(),
          event.actor(),
          event.action().name(),
          event.subject().name(),
          event.subjectId(),
          event.result().name(),
          event.reason());
    }
  }

  @GetMapping
  @Access.ReadAudit
  public AuditView recent(
      @RequestParam(required = false) String subject,
      @RequestParam(defaultValue = "50") @Min(1) @Max(ListAudit.MAX_LIMIT) int limit) {
    return new AuditView(
        audit.recent(Optional.ofNullable(subject).filter(s -> !s.isBlank()), limit).stream()
            .map(Line::of)
            .toList());
  }
}
