package com.jerome.slledger.adapter.web.client;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the market data source is and how long to wait for it. The connect timeout bounds the TCP
 * handshake; the read timeout bounds the wait for the response once connected. Absent or zero means
 * no bound at all, which is what {@code docs/decisions/07-web.md} shows exhausting the server's
 * threads.
 */
@ConfigurationProperties("ledger.marketdata")
public record MarketDataProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {

  static boolean bounded(Duration timeout) {
    return timeout != null && !timeout.isZero();
  }
}
