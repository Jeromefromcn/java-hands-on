package com.jerome.slledger.adapter.web.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jerome.slledger.adapter.web.testing.ProbeController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProbeController.class)
class TraceIdFilterTest {

  @Autowired MockMvc mvc;

  @AfterEach
  void reset() {
    ProbeController.reset();
    MDC.clear();
  }

  @Test
  @DisplayName("every response carries a trace id in the W3C shape, different per request")
  void everyResponseHasItsOwnId() throws Exception {
    String first = traceIdOf("/probe");
    String second = traceIdOf("/probe");

    assertThat(first).matches("[0-9a-f]{32}");
    assertThat(second).matches("[0-9a-f]{32}").isNotEqualTo(first);
  }

  @Test
  @DisplayName("a request to an unknown path still gets a trace id")
  void unknownPathHasId() throws Exception {
    assertThat(traceIdOf("/no-such-path")).matches("[0-9a-f]{32}");
  }

  @Test
  @DisplayName("an incoming X-Trace-Id is ignored, not echoed")
  void incomingIdIgnored() throws Exception {
    String sent = "attacker-chosen\nFAKE LOG LINE";

    String answered =
        mvc.perform(get("/probe").header(TraceIdFilter.HEADER, sent))
            .andReturn()
            .getResponse()
            .getHeader(TraceIdFilter.HEADER);

    assertThat(answered).isNotEqualTo(sent).matches("[0-9a-f]{32}");
  }

  @Test
  @DisplayName("the handler sees the id in the MDC, and the thread is clean afterwards")
  void mdcIsSetDuringAndClearedAfter() throws Exception {
    ProbeController.answer(() -> MDC.get(TraceIdFilter.MDC_KEY));

    var response = mvc.perform(get("/probe")).andReturn().getResponse();

    assertThat(response.getContentAsString()).isEqualTo(response.getHeader(TraceIdFilter.HEADER));
    // MockMvc serves the request on this thread, so this is the thread that served it.
    assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("a request whose handler throws leaves no trace id on the thread")
  void mdcClearedAfterException() throws Exception {
    ProbeController.throwing(new IllegalStateException("boom"));

    int status = mvc.perform(get("/probe")).andReturn().getResponse().getStatus();

    assertThat(status).isEqualTo(500);
    assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
  }

  private String traceIdOf(String path) throws Exception {
    return mvc.perform(get(path)).andReturn().getResponse().getHeader(TraceIdFilter.HEADER);
  }
}
