package com.jerome.slledger.adapter.web.quote;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jerome.slledger.application.quote.OverloadedException;
import com.jerome.slledger.application.quote.PriceFanOut;
import com.jerome.slledger.application.quote.QuoteOutcome;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QuotesController.class)
class QuotesControllerTest {

  // Valid check digits; IsinValidatorTest pins them.
  private static final String HK = "HK0000000007";
  private static final String HK2 = "HK0000000015";

  @Autowired MockMvc mvc;
  @MockitoBean PriceFanOut fanOut;

  @Test
  @DisplayName("a priced item carries its amount as a string, a failed one carries an error type")
  void itemsAreEitherPricedOrFailed() throws Exception {
    when(fanOut.quote(anyList()))
        .thenReturn(
            List.of(
                new QuoteOutcome.Priced(
                    new Isin(HK), Money.of("41.30", Currency.getInstance("HKD"))),
                new QuoteOutcome.Failed(new Isin(HK2), QuoteOutcome.Kind.TIMED_OUT)));

    mvc.perform(get("/quotes").param("isin", HK, HK2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quotes[0].price.amount").value("41.30"))
        .andExpect(jsonPath("$.quotes[0].price.currency").value("HKD"))
        .andExpect(jsonPath("$.quotes[0].error").doesNotExist())
        .andExpect(jsonPath("$.quotes[1].price").doesNotExist())
        .andExpect(
            jsonPath("$.quotes[1].error.type").value("urn:sl-ledger:problem:market-data-timeout"));
  }

  @Test
  @DisplayName("more ISINs than the configured cap is a 400 and no work is started")
  void overTheCap() throws Exception {
    var request = get("/quotes");
    for (int i = 0; i < 21; i++) {
      request.param("isin", HK);
    }
    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("urn:sl-ledger:problem:invalid-query"));
    verify(fanOut, never()).quote(anyList());
  }

  @Test
  @DisplayName("a wrong check digit is a 400")
  void badCheckDigit() throws Exception {
    mvc.perform(get("/quotes").param("isin", "HK0000000001")).andExpect(status().isBadRequest());
    verify(fanOut, never()).quote(anyList());
  }

  @Test
  @DisplayName("no ISIN at all is a 400")
  void none() throws Exception {
    mvc.perform(get("/quotes")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an overloaded executor is a 503 with Retry-After and a stable type")
  void overloaded() throws Exception {
    when(fanOut.quote(anyList())).thenThrow(new OverloadedException("full"));

    mvc.perform(get("/quotes").param("isin", HK))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.type").value("urn:sl-ledger:problem:overloaded"));
  }
}
