package com.jerome.slledger.application.port;

/** The market data source did not answer in time, or could not be reached at all. */
public class MarketDataUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MarketDataUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
