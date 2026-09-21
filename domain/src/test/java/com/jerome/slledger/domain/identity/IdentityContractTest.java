package com.jerome.slledger.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.error.MalformedIdentifierException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The equals/hashCode contract, and what actually happens when it is broken.
 *
 * <p>ROADMAP stage 2 asks for a hashCode built from only some of the fields, put into a HashMap,
 * and then failing to come back. It comes back. A partial-field hashCode still satisfies the
 * contract, so it costs collisions, not entries. The first test below asserts exactly that, and the
 * two reproductions after it are the cases that genuinely lose the entry.
 *
 * <p>See {@code docs/decisions/02-domain-model.md}.
 */
class IdentityContractTest {

  @Test
  void identifiersAreValueObjectsAndCompareByValue() {
    assertThat(new CounterpartyId("CP-0001")).isEqualTo(new CounterpartyId("CP-0001"));
    assertThat(new LoanPositionId("LP-0001")).isEqualTo(new LoanPositionId("LP-0001"));
    assertThat(new Isin("HK0000000001")).isEqualTo(new Isin("HK0000000001"));
  }

  @Test
  void anIdentifierCannotBeBlank() {
    assertThatExceptionOfType(MalformedIdentifierException.class)
        .isThrownBy(() -> new CounterpartyId("   "))
        .withMessageContaining("CounterpartyId");
  }

  @Test
  void anIsinMustBeTwelveCharactersOfTheRightShape() {
    assertThatExceptionOfType(MalformedIdentifierException.class)
        .isThrownBy(() -> new Isin("HK123"));
    assertThatExceptionOfType(MalformedIdentifierException.class)
        .isThrownBy(() -> new Isin("hk0000000001"));
    assertThatExceptionOfType(MalformedIdentifierException.class)
        .isThrownBy(() -> new Isin("00HK00000001"));
  }

  // --- what the roadmap asked for, and what it actually does -------------------------------

  @Test
  void partialFieldHashCodeStillRetrievesItsEntry() {
    Map<PartialHashCodeKey, String> map = new HashMap<>();
    map.put(new PartialHashCodeKey("CP-0001", "HK0000000001"), "found");

    String retrieved = map.get(new PartialHashCodeKey("CP-0001", "HK0000000001"));

    // The contract holds: equal objects still hash equally. Two keys that differ only in the
    // field hashCode ignores land in the same bucket and are then separated by equals. The cost
    // is collisions, not correctness.
    assertThat(retrieved).isEqualTo("found");
    assertThat(new PartialHashCodeKey("CP-0001", "HK0000000001"))
        .isNotEqualTo(new PartialHashCodeKey("CP-0001", "HK0000000002"));
    assertThat(new PartialHashCodeKey("CP-0001", "HK0000000001").hashCode())
        .isEqualTo(new PartialHashCodeKey("CP-0001", "HK0000000002").hashCode());
  }

  // --- the two that genuinely lose the entry ------------------------------------------------

  @Test
  void reproducesHashMapLookupMissWhenHashCodeIsNotOverridden() {
    Map<EqualsWithoutHashCodeKey, String> map = new HashMap<>();
    map.put(new EqualsWithoutHashCodeKey("CP-0001"), "found");

    EqualsWithoutHashCodeKey equalButDistinct = new EqualsWithoutHashCodeKey("CP-0001");

    // equals says the two keys are the same; Object.hashCode says they are not. The lookup never
    // reaches equals, because it goes to the wrong bucket first.
    assertThat(equalButDistinct).isEqualTo(new EqualsWithoutHashCodeKey("CP-0001"));
    assertThat(map.get(equalButDistinct)).isNull();
    assertThat(map).containsValue("found");
  }

  @Test
  void reproducesHashMapLookupMissAfterMutatingAKeyField() {
    MutableKey key = new MutableKey("CP-0001");
    Map<MutableKey, String> map = new HashMap<>();
    map.put(key, "found");

    key.rename("CP-0002");

    // Same object, same reference, still in the map — stranded in the bucket its old hash chose.
    assertThat(map.get(key)).isNull();
    assertThat(map.get(new MutableKey("CP-0001"))).isNull();
    assertThat(map).containsValue("found");
    assertThat(map).hasSize(1);
  }

  /** hashCode from one field, equals from both. The roadmap's version. */
  private static final class PartialHashCodeKey {
    private final String counterparty;
    private final String isin;

    PartialHashCodeKey(String counterparty, String isin) {
      this.counterparty = counterparty;
      this.isin = isin;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof PartialHashCodeKey other
          && counterparty.equals(other.counterparty)
          && isin.equals(other.isin);
    }

    @Override
    public int hashCode() {
      return counterparty.hashCode();
    }
  }

  /** equals overridden, hashCode inherited from Object. */
  private static final class EqualsWithoutHashCodeKey {
    private final String counterparty;

    EqualsWithoutHashCodeKey(String counterparty) {
      this.counterparty = counterparty;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof EqualsWithoutHashCodeKey other && counterparty.equals(other.counterparty);
    }

    // No hashCode. That omission is the bug being reproduced, and javac does not warn about
    // it even under -Xlint:all -Werror — which is the second half of the lesson.
  }

  /** A field inside hashCode that can change after the key is in the map. */
  private static final class MutableKey {
    private String counterparty;

    MutableKey(String counterparty) {
      this.counterparty = counterparty;
    }

    void rename(String counterparty) {
      this.counterparty = counterparty;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof MutableKey other && counterparty.equals(other.counterparty);
    }

    @Override
    public int hashCode() {
      return Objects.hash(counterparty);
    }
  }
}
