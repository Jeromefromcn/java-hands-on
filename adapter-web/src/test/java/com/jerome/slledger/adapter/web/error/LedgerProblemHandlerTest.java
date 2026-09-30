package com.jerome.slledger.adapter.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jerome.slledger.adapter.web.testing.Evidence;
import com.jerome.slledger.adapter.web.testing.ProbeController;
import com.jerome.slledger.adapter.web.trace.TraceIdFilter;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.error.ExcessiveReturnException;
import com.jerome.slledger.domain.error.IllegalTransitionException;
import com.jerome.slledger.domain.error.InsufficientCreditException;
import com.jerome.slledger.domain.error.MalformedIdentifierException;
import com.jerome.slledger.domain.error.NotABusinessDayException;
import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProbeController.class)
class LedgerProblemHandlerTest {

  private static final Currency HKD = Currency.getInstance("HKD");

  @Autowired MockMvc mvc;

  @AfterEach
  void reset() {
    ProbeController.reset();
  }

  static Stream<Arguments> thrown() {
    return Stream.of(
        Arguments.of(UnknownReferenceException.subject("position", "P-1"), 404, "not-found"),
        Arguments.of(
            UnknownReferenceException.reference("counterparty", "CP-9"), 422, "unknown-reference"),
        Arguments.of(
            UnknownReferenceException.subject("security", "HK0000000007"), 404, "not-found"),
        Arguments.of(
            UnknownReferenceException.reference("security", "HK0000000001"),
            422,
            "unknown-reference"),
        Arguments.of(
            new MalformedIdentifierException("Isin", "x", "shape"), 400, "malformed-identifier"),
        Arguments.of(
            new InsufficientCreditException(
                Money.of("100.00", HKD), Money.of("90.00", HKD), Money.of("20.00", HKD)),
            422,
            "insufficient-credit"),
        Arguments.of(new ExcessiveReturnException(20, 10), 422, "excessive-return"),
        Arguments.of(
            new CurrencyMismatchException(HKD, Currency.getInstance("USD")),
            422,
            "currency-mismatch"),
        Arguments.of(new IllegalTransitionException("CLOSED", "return"), 409, "illegal-transition"),
        Arguments.of(new NotABusinessDayException(LocalDate.of(2026, 10, 1)), 422, "domain-rule"),
        Arguments.of(new OptimisticLockingFailureException("lost"), 409, "concurrent-update"),
        Arguments.of(new PessimisticLockingFailureException("timeout"), 409, "concurrent-update"));
  }

  @ParameterizedTest(name = "{0} -> {1} {2}")
  @MethodSource("thrown")
  @DisplayName("each exception maps to its status and problem type")
  void mapsException(RuntimeException exception, int status, String kind) throws Exception {
    ProbeController.throwing(exception);

    mvc.perform(get("/probe"))
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(Problems.PREFIX + kind))
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  @DisplayName("a 500 leaks no message, class name or stack frame, and carries the trace id")
  void unexpectedLeaksNothing() throws Exception {
    ProbeController.throwing(
        new IllegalStateException("SECRET-DETAIL connection to db-internal-7 refused"));

    MockHttpServletResponse response = mvc.perform(get("/probe")).andReturn().getResponse();
    String body = response.getContentAsString();

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(body)
        .contains(Problems.PREFIX + "internal")
        .contains(response.getHeader(TraceIdFilter.HEADER))
        .doesNotContain("SECRET-DETAIL")
        .doesNotContain("db-internal-7")
        .doesNotContain("IllegalStateException")
        .doesNotContain("com.jerome")
        .doesNotContain("java.")
        .doesNotContain("at ");
    Evidence.write(
        "07-problem-500.txt",
        """
        Stage 07 · section 4 — what a 500 says (LedgerProblemHandlerTest.unexpectedLeaksNothing)

        thrown:   IllegalStateException("SECRET-DETAIL connection to db-internal-7 refused")
        status:   %d
        headers:  Content-Type: %s
                  X-Trace-Id: %s
        body:     %s

        The message, the exception type and the stack stay in the server log, under the same
        trace id.
        """
            .formatted(
                response.getStatus(),
                response.getContentType(),
                response.getHeader(TraceIdFilter.HEADER),
                body));
  }

  @Test
  @DisplayName("a field that fails validation is a 400 naming the field")
  void validation() throws Exception {
    mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(Problems.PREFIX + "validation"))
        .andExpect(jsonPath("$.errors[0].field").value("value"))
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  @DisplayName("a money amount sent as a JSON number is a 400 naming the path")
  void moneyAsNumber() throws Exception {
    mvc.perform(
            post("/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"v\",\"money\":{\"amount\":12.50,\"currency\":\"HKD\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(Problems.PREFIX + "malformed-body"))
        .andExpect(jsonPath("$.path").value("money"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.jerome"))));
  }

  @Test
  @DisplayName("a body that is not JSON is a 400")
  void notJson() throws Exception {
    mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{nope"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(Problems.PREFIX + "malformed-body"));
  }

  @Test
  @DisplayName("a missing required header is a 400 naming it")
  void missingHeader() throws Exception {
    mvc.perform(get("/probe/header"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(Problems.PREFIX + "missing-header"))
        .andExpect(
            jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
  }

  @Test
  @DisplayName("framework errors are ProblemDetails with a kind and a trace id too")
  void frameworkErrors() throws Exception {
    mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(Problems.PREFIX + "unsupported-media-type"))
        .andExpect(jsonPath("$.traceId").exists());
    mvc.perform(get("/no-such-path"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(Problems.PREFIX + "not-found"))
        .andExpect(jsonPath("$.traceId").exists());
  }
}
