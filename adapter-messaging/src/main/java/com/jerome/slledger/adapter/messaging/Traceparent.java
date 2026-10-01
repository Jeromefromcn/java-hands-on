package com.jerome.slledger.adapter.messaging;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Reads the trace context a record carries, if it carries one. */
final class Traceparent {

  private Traceparent() {}

  /** The {@code traceparent} header's value, or null. */
  static String of(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader(KafkaEventSender.TRACEPARENT);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
