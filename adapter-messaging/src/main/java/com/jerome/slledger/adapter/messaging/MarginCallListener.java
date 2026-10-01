package com.jerome.slledger.adapter.messaging;

import com.jerome.slledger.application.messaging.EventCodec;
import com.jerome.slledger.application.messaging.IntegrationEvent;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.MarginCallNotifier;
import com.jerome.slledger.application.messaging.MessageDecodeException;
import com.jerome.slledger.application.observability.Tracing;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.MessageListener;

/** The consumer of {@code sl.margin-calls}: decode, notify once. */
public class MarginCallListener implements MessageListener<String, String> {

  private final EventCodec codec;
  private final MarginCallNotifier notifier;
  private final Tracing tracing;

  public MarginCallListener(EventCodec codec, MarginCallNotifier notifier) {
    this(codec, notifier, Tracing.NONE);
  }

  public MarginCallListener(EventCodec codec, MarginCallNotifier notifier, Tracing tracing) {
    this.codec = codec;
    this.notifier = notifier;
    this.tracing = tracing;
  }

  @Override
  public void onMessage(ConsumerRecord<String, String> record) {
    tracing.inSpan("consume " + record.topic(), Traceparent.of(record), () -> handle(record));
  }

  private void handle(ConsumerRecord<String, String> record) {
    Header type = record.headers().lastHeader(KafkaEventSender.EVENT_TYPE);
    String typeName =
        type == null ? MarginCallRaised.TYPE : new String(type.value(), StandardCharsets.UTF_8);
    IntegrationEvent event = codec.decode(typeName, record.value());
    if (!(event instanceof MarginCallRaised call)) {
      throw new MessageDecodeException(
          "expected a MarginCallRaised on " + record.topic() + ", got " + typeName);
    }
    notifier.handle(call);
  }
}
