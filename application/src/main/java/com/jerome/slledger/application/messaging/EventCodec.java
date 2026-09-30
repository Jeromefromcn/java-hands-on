package com.jerome.slledger.application.messaging;

/**
 * The wire form of {@link IntegrationEvent}, kept out of {@code application} so that the JSON
 * library is an adapter's choice.
 */
public interface EventCodec {

  String encode(IntegrationEvent event);

  /**
   * @throws MessageDecodeException if the payload is not exactly what {@link #encode} writes for
   *     that type
   */
  IntegrationEvent decode(String type, String payload);
}
