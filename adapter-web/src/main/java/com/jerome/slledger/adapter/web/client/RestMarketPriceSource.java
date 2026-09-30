package com.jerome.slledger.adapter.web.client;

import com.jerome.slledger.application.port.MarketDataRejectedException;
import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.net.http.HttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code GET {base-url}/prices/{isin}} through {@link RestClient}, on the JDK's {@link HttpClient}.
 *
 * <p>The builder is Boot's, so the answer is read with the application's {@code JsonMapper} and
 * {@code LedgerJacksonModule}: a price from outside is held to the same money rules as a request
 * body. Nothing is retried and nothing is cached; stage 12 adds both, with a circuit breaker.
 */
@Component
public class RestMarketPriceSource implements MarketPriceSource {

  private final RestClient client;

  RestMarketPriceSource(RestClient.Builder builder, MarketDataProperties properties) {
    HttpClient.Builder http = HttpClient.newBuilder();
    if (MarketDataProperties.bounded(properties.connectTimeout())) {
      http.connectTimeout(properties.connectTimeout());
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
    if (MarketDataProperties.bounded(properties.readTimeout())) {
      factory.setReadTimeout(properties.readTimeout());
    }
    this.client = builder.baseUrl(properties.baseUrl().toString()).requestFactory(factory).build();
  }

  record PriceResponse(String isin, Money price) {}

  @Override
  public Money priceOf(Isin isin) {
    PriceResponse answer;
    try {
      answer =
          client.get().uri("/prices/{isin}", isin.value()).retrieve().body(PriceResponse.class);
    } catch (HttpStatusCodeException status) {
      if (status.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
        throw UnknownReferenceException.subject("security", isin.value());
      }
      throw new MarketDataRejectedException(
          "The market data source answered " + status.getStatusCode().value(), status);
    } catch (ResourceAccessException unreachable) {
      throw new MarketDataUnavailableException(
          "The market data source did not answer: " + unreachable.getMostSpecificCause(),
          unreachable);
    } catch (RestClientException unreadable) {
      throw new MarketDataRejectedException(
          "The market data source's answer was refused: "
              + unreadable.getMostSpecificCause().getMessage(),
          unreadable);
    }
    if (answer == null || answer.price() == null || !isin.value().equals(answer.isin())) {
      throw new MarketDataRejectedException(
          "The market data source answered for another security, or with no price");
    }
    return answer.price();
  }
}
