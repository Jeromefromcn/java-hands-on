package com.jerome.slledger.adapter.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Who may call a handler, one annotation per capability, so the authorization model is these five
 * lines and a handler says what it needs by name. The role names are the identity provider's, as
 * {@code Role} spells them; method security runs before the transaction a use case opens, so a
 * refused call never takes a connection.
 *
 * <p>Every handler must carry one: {@code ArchitectureIT} fails the build for a handler without.
 * The reading ones admit {@code ledger-counterparty}, whose rows the use cases then narrow to its
 * own; whether a role may call is decided here, whose rows it sees is decided in the query.
 */
public final class Access {

  private Access() {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  @PreAuthorize("hasAnyRole('ledger-trader', 'ledger-ops', 'ledger-counterparty')")
  public @interface ReadPositions {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  @PreAuthorize("hasRole('ledger-trader')")
  public @interface BookPositions {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  @PreAuthorize("hasAnyRole('ledger-trader', 'ledger-ops')")
  public @interface ReadQuotes {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  @PreAuthorize("hasRole('ledger-auditor')")
  public @interface ReadAudit {}

  /**
   * Stage 14: read and change rollouts. Operations staff only; which of them may approve a given
   * raise (not its maker) is the use case's rule, not a role.
   */
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @Documented
  @PreAuthorize("hasRole('ledger-ops')")
  public @interface ManageRollouts {}
}
