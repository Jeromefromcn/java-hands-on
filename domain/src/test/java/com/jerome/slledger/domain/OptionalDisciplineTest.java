package com.jerome.slledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Why Optional is a return type and nothing else.
 *
 * <p>The usual argument for this rule is a style opinion, so this stage does not make it. It makes
 * a runtime failure instead: Optional does not implement Serializable, deliberately, and a field of
 * that type breaks any mechanism that walks an object's fields. Serialization here; the same class
 * of mechanism in stage 6's persistence mapping and stage 10's message payloads.
 *
 * <p>See {@code docs/decisions/02-domain-model.md} and {@code
 * docs/evidence/02-optional-serialization.txt}.
 */
class OptionalDisciplineTest {

  @Test
  void optionalWasNeverMeantToBeAField() {
    // Not an oversight. Optional was specified as a return type for a method that might have
    // nothing to return, and leaving Serializable off is how that intent is expressed in the
    // type system rather than in the javadoc.
    assertThat(Serializable.class.isAssignableFrom(Optional.class)).isFalse();
  }

  @Test
  void reproducesOptionalFieldSerialization() {
    WithOptionalField subject = new WithOptionalField(Optional.of("CP-0001"));

    assertThatExceptionOfType(NotSerializableException.class)
        .isThrownBy(() -> serialize(subject))
        .withMessageContaining("java.util.Optional");
  }

  @Test
  void anOptionalFieldFailsEvenWhenItIsEmpty() {
    // The failure is about the declared field type, not its contents. An empty Optional is
    // still an Optional instance, so a test suite that only ever populated the field would have
    // found this and one that only ever left it empty would also have found it — but a codebase
    // where the field is usually null finds it in production.
    assertThatExceptionOfType(NotSerializableException.class)
        .isThrownBy(() -> serialize(new WithOptionalField(Optional.empty())));
  }

  @Test
  void aNullableFieldWithAnOptionalReturningAccessorIsFine() throws Exception {
    // The shape this domain uses: the field is nullable, the accessor returns Optional, and
    // nothing that walks fields ever sees an Optional. LoanPosition.revalue is the one place
    // in the domain that returns one.
    WithNullableField present = new WithNullableField("CP-0001");
    WithNullableField absent = new WithNullableField(null);

    assertThat(roundTrip(present).counterparty()).contains("CP-0001");
    assertThat(roundTrip(absent).counterparty()).isEmpty();
  }

  private static byte[] serialize(Object subject) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(subject);
    }
    return bytes.toByteArray();
  }

  private static WithNullableField roundTrip(WithNullableField subject) throws Exception {
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(serialize(subject)))) {
      return (WithNullableField) in.readObject();
    }
  }

  /**
   * The violation. Confined to this file, as the spec requires.
   *
   * <p>The suppression is itself a finding. javac under {@code -Xlint:all -Werror} refuses to
   * compile this class — "non-transient instance field of a serializable class declared with a
   * non-serializable type" — so on this build the mistake never reaches the runtime failure below.
   * Reproducing it at all requires opting out by name. See {@code
   * docs/evidence/02-optional-serialization.txt}.
   */
  @SuppressWarnings("serial")
  private static final class WithOptionalField implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Optional<String> counterparty;

    WithOptionalField(Optional<String> counterparty) {
      this.counterparty = counterparty;
    }

    Optional<String> counterparty() {
      return counterparty;
    }
  }

  /** The shape the domain actually uses. */
  private static final class WithNullableField implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String counterparty;

    WithNullableField(String counterparty) {
      this.counterparty = counterparty;
    }

    Optional<String> counterparty() {
      return Optional.ofNullable(counterparty);
    }
  }
}
