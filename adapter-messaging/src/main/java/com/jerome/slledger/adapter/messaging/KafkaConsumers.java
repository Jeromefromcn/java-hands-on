package com.jerome.slledger.adapter.messaging;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;

/**
 * The one consumer configuration the ledger uses.
 *
 * <p>Offsets are committed by the container after the listener has returned, one record at a time
 * ({@code AckMode.RECORD}), and never by a timer: {@code enable.auto.commit=false}. A committed
 * offset therefore means "the effect of every record up to here has happened", and a crash leaves
 * the last record to be delivered again, which the consumer's guard makes harmless. The other two
 * orders, commit before the effect and commit by a clock, each lose a message, and are reproduced
 * in {@code OffsetCommitIT}.
 */
public final class KafkaConsumers {

  private KafkaConsumers() {}

  /**
   * @param maxPollRecords records handed to the listener per poll; with {@code maxPollInterval}
   *     this bounds how slow one record may be: {@code maxPollRecords × worst time per record} must
   *     stay below {@code maxPollInterval}, or the consumer is removed from its group
   * @param sessionTimeout how long the broker waits for a heartbeat before it declares this
   *     consumer dead and reassigns its partitions
   */
  public record Settings(
      String bootstrapServers,
      String groupId,
      int concurrency,
      int maxPollRecords,
      Duration maxPollInterval,
      Duration sessionTimeout) {

    public static Settings of(String bootstrapServers, String groupId) {
      return new Settings(
          bootstrapServers, groupId, 1, 50, Duration.ofMinutes(5), Duration.ofSeconds(45));
    }
  }

  public static ConcurrentMessageListenerContainer<String, String> container(
      Settings settings,
      String topic,
      MessageListener<String, String> listener,
      CommonErrorHandler errorHandler) {
    return container(settings, topic, listener, errorHandler, List.of());
  }

  /**
   * @param factoryListeners told when the factory creates or closes a consumer; how a metrics
   *     registry gets the consumers' own lag and fetch meters without this module knowing one
   */
  public static ConcurrentMessageListenerContainer<String, String> container(
      Settings settings,
      String topic,
      MessageListener<String, String> listener,
      CommonErrorHandler errorHandler,
      List<ConsumerFactory.Listener<String, String>> factoryListeners) {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers());
    config.put(ConsumerConfig.GROUP_ID_CONFIG, settings.groupId());
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    // A group that has committed nothing starts from the beginning of the topic, not from now: a
    // price update published before the first consumer started is still a price update.
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, settings.maxPollRecords());
    config.put(
        ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, (int) settings.maxPollInterval().toMillis());
    config.put(
        ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, (int) settings.sessionTimeout().toMillis());

    ContainerProperties properties = new ContainerProperties(topic);
    properties.setMessageListener(listener);
    properties.setAckMode(ContainerProperties.AckMode.RECORD);
    properties.setGroupId(settings.groupId());
    // Stage 14: on shutdown, finish the record in hand, commit it, and stop. By default the
    // container first handles the rest of the last poll, up to max.poll.records of them, which is
    // a drain whose length nobody chose and which a pod's grace period cuts at an arbitrary record.
    // The records not handled are not committed and go to the next member of the group.
    // ConsumerShutdownIT measures both. See docs/decisions/14-delivery.md.
    properties.setStopImmediate(true);

    DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(config);
    factoryListeners.forEach(factory::addListener);
    ConcurrentMessageListenerContainer<String, String> container =
        new ConcurrentMessageListenerContainer<>(factory, properties);
    container.setConcurrency(settings.concurrency());
    if (errorHandler != null) {
      container.setCommonErrorHandler(errorHandler);
    }
    return container;
  }
}
