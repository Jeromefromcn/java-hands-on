package com.jerome.slledger.adapter.messaging;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The one producer configuration the ledger uses: idempotent, {@code acks=all}, and every wait
 * bounded. An unbounded producer wait is a request thread, or the relay's transaction, held for as
 * long as the broker is away.
 */
public final class KafkaProducers {

  private KafkaProducers() {}

  /**
   * @param deliveryTimeout the most time from {@code send} to an ack or a failure, retries included
   * @param maxBlock the most time {@code send} itself may block on metadata or a full buffer
   */
  public static KafkaTemplate<String, String> template(
      String bootstrapServers, Duration deliveryTimeout, Duration maxBlock) {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryTimeout.toMillis());
    config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) deliveryTimeout.toMillis() / 2);
    config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) maxBlock.toMillis());
    return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
  }
}
