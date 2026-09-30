package com.jerome.slledger.application.port;

/**
 * The market data source answered, and the answer was refused: an error status, or a price that
 * breaks the money invariant (a number, over-precise, the wrong currency, the wrong security).
 */
public class MarketDataRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MarketDataRejectedException(String message) {
    super(message);
  }

  public MarketDataRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
