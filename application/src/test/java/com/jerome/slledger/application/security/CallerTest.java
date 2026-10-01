package com.jerome.slledger.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallerTest {

  private static final CounterpartyId A = new CounterpartyId("CP-001");
  private static final CounterpartyId B = new CounterpartyId("CP-002");

  private static Caller caller(Optional<CounterpartyId> cp, Role... roles) {
    return new Caller("sub", Set.of(roles), cp);
  }

  @Test
  @DisplayName("staff see every counterparty, with or without a counterparty claim")
  void staffSeeAll() {
    assertThat(caller(Optional.empty(), Role.TRADER).visibility()).isEqualTo(Visibility.ALL);
    assertThat(caller(Optional.empty(), Role.OPS).visibility()).isEqualTo(Visibility.ALL);
    assertThat(caller(Optional.of(A), Role.OPS, Role.COUNTERPARTY).visibility())
        .isEqualTo(Visibility.ALL);
  }

  @Test
  @DisplayName("a counterparty sees its own rows and no one else's")
  void counterpartySeesOwn() {
    Visibility visibility = caller(Optional.of(A), Role.COUNTERPARTY).visibility();

    assertThat(visibility.permits(A)).isTrue();
    assertThat(visibility.permits(B)).isFalse();
  }

  @Test
  @DisplayName("a counterparty role with no counterparty claim sees nothing")
  void counterpartyWithoutClaimSeesNothing() {
    Visibility visibility = caller(Optional.empty(), Role.COUNTERPARTY).visibility();

    assertThat(visibility).isEqualTo(Visibility.NOBODY);
    assertThat(visibility.permits(A)).isFalse();
    assertThatThrownBy(() -> visibility.narrow(Optional.empty()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("roles that do not read positions see nothing")
  void otherRolesSeeNothing() {
    assertThat(caller(Optional.of(A), Role.AUDITOR, Role.METRICS).visibility())
        .isEqualTo(Visibility.NOBODY);
    assertThat(caller(Optional.of(A)).visibility()).isEqualTo(Visibility.NOBODY);
  }

  @Test
  @DisplayName("narrow: a scoped caller is pinned to its own counterparty or refused")
  void narrow() {
    Visibility own = Visibility.only(A);

    assertThat(own.narrow(Optional.empty())).contains(A);
    assertThat(own.narrow(Optional.of(A))).contains(A);
    assertThatThrownBy(() -> own.narrow(Optional.of(B))).isInstanceOf(ForbiddenException.class);
    assertThat(Visibility.ALL.narrow(Optional.empty())).isEmpty();
    assertThat(Visibility.ALL.narrow(Optional.of(B))).contains(B);
  }

  @Test
  @DisplayName("only the five roles of this ledger exist; any other name in a token is nothing")
  void roleClaims() {
    assertThat(Role.fromClaim("ledger-trader")).contains(Role.TRADER);
    assertThat(Role.fromClaim("admin")).isEmpty();
    assertThat(Role.fromClaim("ROLE_ledger-trader")).isEmpty();
  }
}
