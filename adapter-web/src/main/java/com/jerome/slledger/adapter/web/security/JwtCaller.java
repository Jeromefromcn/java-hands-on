package com.jerome.slledger.adapter.web.security;

import com.jerome.slledger.application.security.Caller;
import com.jerome.slledger.application.security.Callers;
import com.jerome.slledger.application.security.Role;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads a {@link Caller} out of a verified token, and is the {@link Callers} of a web request.
 *
 * <p>The claims are Keycloak's: roles in {@code realm_access.roles}, the counterparty an external
 * borrower speaks for in {@code counterparty_id}. A role name this ledger does not define is
 * dropped, not mapped.
 */
@Component
public class JwtCaller implements Callers {

  static final String COUNTERPARTY_CLAIM = "counterparty_id";

  public static Caller of(Jwt jwt) {
    return new Caller(jwt.getSubject(), roles(jwt), counterparty(jwt));
  }

  static Set<Role> roles(Jwt jwt) {
    Object realmAccess = jwt.getClaim("realm_access");
    Set<Role> roles = EnumSet.noneOf(Role.class);
    if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> names) {
      for (Object name : names) {
        if (name instanceof String claim) {
          Role.fromClaim(claim).ifPresent(roles::add);
        }
      }
    }
    return roles;
  }

  private static Optional<CounterpartyId> counterparty(Jwt jwt) {
    String claim = jwt.getClaimAsString(COUNTERPARTY_CLAIM);
    return claim == null || claim.isBlank()
        ? Optional.empty()
        : Optional.of(new CounterpartyId(claim));
  }

  /**
   * The caller of the request on this thread. With no authentication at all (a job, a consumer) it
   * is {@link Caller#SYSTEM}; with an anonymous or non-token authentication it is an error, because
   * a web request that reached a use case unauthenticated is a hole, not a system caller.
   */
  @Override
  public Caller current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return Caller.SYSTEM;
    }
    if (authentication instanceof AnonymousAuthenticationToken
        || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      throw new IllegalStateException("The request is not authenticated with a bearer token");
    }
    return of(jwt);
  }
}
