package com.jerome.slledger.bootstrap.security;

import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.application.position.ShowPosition;
import com.jerome.slledger.application.security.Visibility;
import com.jerome.slledger.domain.loan.LoanPositionId;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoint a team writes when "secured" means a role check: it asks that the caller is a
 * counterparty and never whose position it reads. Test-only, registered by a test that reproduces
 * the over-read; it is not on the production classpath. See docs/decisions/13-security.md.
 */
@RestController
public class VulnerableLoanController {

  private final ShowPosition show;

  VulnerableLoanController(ShowPosition show) {
    this.show = show;
  }

  @GetMapping("/vulnerable/loans/{id}")
  @PreAuthorize("hasRole('ledger-counterparty')")
  public PositionRow read(@PathVariable String id) {
    return show.show(Visibility.ALL, new LoanPositionId(id));
  }
}
