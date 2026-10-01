package com.jerome.slledger.adapter.messaging;

import com.jerome.slledger.application.messaging.ApplyPriceUpdate;
import com.jerome.slledger.application.messaging.EventCodec;
import com.jerome.slledger.application.messaging.IntegrationEvent;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.MessageDecodeException;
import com.jerome.slledger.application.observability.Tracing;
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
  private final Tracing tracing;

  public PriceUpdateListener(EventCodec codec, ApplyPriceUpdate apply) {
    this(codec, apply, Tracing.NONE);
  }

  public PriceUpdateListener(EventCodec codec, ApplyPriceUpdate apply, Tracing tracing) {
    this.codec = codec;
    this.apply = apply;
    this.tracing = tracing;
  }

  @Override
  public void onMessage(ConsumerRecord<String, String> record) {
    tracing.inSpan("consume " + record.topic(), Traceparent.of(record), () -> handle(record));
  }

  private void handle(ConsumerRecord<String, String> record) {
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
