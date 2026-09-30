package com.jerome.slledger.adapter.messaging;

import com.jerome.slledger.application.messaging.ApplyPriceUpdate;
import com.jerome.slledger.application.messaging.EventCodec;
import com.jerome.slledger.application.messaging.IntegrationEvent;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.MessageDecodeException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.MessageListener;

/**
 * The consumer of {@code sl.price-updates}: decode, apply. A record that does not decode is a
 * {@link MessageDecodeException}, which the error handler sends to the dead-letter topic without
 * retrying; anything the use case throws is classified there, not here.
 */
public class PriceUpdateListener implements MessageListener<String, String> {

  private final EventCodec codec;
  private final ApplyPriceUpdate apply;

  public PriceUpdateListener(EventCodec codec, ApplyPriceUpdate apply) {
    this.codec = codec;
    this.apply = apply;
  }

  @Override
  public void onMessage(ConsumerRecord<String, String> record) {
    Header type = record.headers().lastHeader(KafkaEventSender.EVENT_TYPE);
    String typeName =
        type == null ? PriceUpdated.TYPE : new String(type.value(), StandardCharsets.UTF_8);
    IntegrationEvent event = codec.decode(typeName, record.value());
    if (!(event instanceof PriceUpdated update)) {
      throw new MessageDecodeException(
          "expected a PriceUpdated on " + record.topic() + ", got " + typeName);
    }
    apply.apply(update);
  }
}
