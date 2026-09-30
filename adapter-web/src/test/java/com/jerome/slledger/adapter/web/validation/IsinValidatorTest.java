package com.jerome.slledger.adapter.web.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jerome.slledger.domain.instrument.Isin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IsinValidatorTest {

  private final IsinValidator validator = new IsinValidator();

  /**
   * Synthetic ISINs, check digits worked by hand. HK000000001: letters expand to 17 20, giving
   * 1720000000001; Luhn from the right doubles 1, 2 and 1 (the last is the H's 1), so 2 + 4 + 7 + 2
   * = 15, and the completing digit is 5.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "HK0000000007",
        "HK0000000015",
        "HK0000000023",
        "US0000000010",
        "JP0000000018",
        "BH0000000016"
      })
  @DisplayName("synthetic ISINs with hand-computed check digits are accepted")
  void accepts(String isin) {
    assertThat(validator.isValid(isin, null)).isTrue();
    assertThat(IsinValidator.checkDigitFor(isin.substring(0, 11))).isEqualTo(isin.charAt(11));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"HK0000000008", "HK0000000016", "US0000000011", "JP0000000017", "BH0000000010"})
  @DisplayName("the same ISINs with the last digit changed are refused")
  void refusesWrongDigit(String isin) {
    assertThat(validator.isValid(isin, null)).isFalse();
  }

  @Test
  @DisplayName("stage 6's fixture ISIN is well-formed to domain and refused here")
  void domainAcceptsWhatTheBoundaryRefuses() {
    assertThatCode(() -> new Isin("HK0000000001")).doesNotThrowAnyException();
    assertThat(validator.isValid("HK0000000001", null)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "hk0000000007",
        "HK000000007",
        "HK00000000007",
        "HK000000000A",
        "1K0000000007"
      })
  @DisplayName("a wrong shape is refused")
  void refusesShape(String isin) {
    assertThat(validator.isValid(isin, null)).isFalse();
  }

  @Test
  @DisplayName("null is left to @NotNull")
  void nullIsValid() {
    assertThat(validator.isValid(null, null)).isTrue();
  }
}
