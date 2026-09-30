package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.adapter.messaging.ErrorHandling;
import com.jerome.slledger.adapter.messaging.JsonEventCodec;
import com.jerome.slledger.adapter.messaging.KafkaConsumers;
import com.jerome.slledger.adapter.messaging.KafkaEventSender;
import com.jerome.slledger.adapter.messaging.KafkaProducers;
import com.jerome.slledger.adapter.messaging.MarginCallListener;
import com.jerome.slledger.adapter.messaging.PriceUpdateListener;
import com.jerome.slledger.application.event.DomainEvents;
import com.jerome.slledger.application.messaging.ApplyPriceUpdate;
import com.jerome.slledger.application.messaging.ConsumedMessages;
import com.jerome.slledger.application.messaging.DedupPurge;
import com.jerome.slledger.application.messaging.EventCodec;
import com.jerome.slledger.application.messaging.EventSender;
import com.jerome.slledger.application.messaging.IdempotentConsumer;
import com.jerome.slledger.application.messaging.MarginCallNotifier;
import com.jerome.slledger.application.messaging.NoticeChannel;
import com.jerome.slledger.application.messaging.Outbox;
import com.jerome.slledger.application.messaging.OutboxRelay;
import com.jerome.slledger.application.messaging.OutboxStore;
import com.jerome.slledger.application.messaging.PublishPriceUpdate;
import com.jerome.slledger.application.messaging.SecurityPositions;
import com.jerome.slledger.application.messaging.SecurityPrices;
import com.jerome.slledger.application.messaging.StoredOutbox;
import com.jerome.slledger.application.messaging.Topics;
import com.jerome.slledger.application.revaluation.RevaluationLauncher;
import com.jerome.slledger.application.revaluation.RevaluationRequests;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/** Kafka, the outbox and its relay. Only present when {@code ledger.messaging.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledger.messaging", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {

  @Bean
  EventCodec eventCodec() {
    return new JsonEventCodec();
  }

  @Bean(destroyMethod = "destroy")
  KafkaTemplate<String, String> ledgerKafkaTemplate(MessagingProperties properties) {
    return KafkaProducers.template(
        properties.bootstrapServers(), properties.sendTimeout(), properties.maxBlock());
  }

  @Bean
  EventSender eventSender(KafkaTemplate<String, String> kafka, MessagingProperties properties) {
    // A little above the producer's own delivery timeout, so its failure is what gets reported.
    return new KafkaEventSender(kafka, properties.sendTimeout().plus(Duration.ofSeconds(2)));
  }

  @Bean
  Outbox outbox(EventCodec codec, OutboxStore store) {
    return new StoredOutbox(codec, store);
  }

  @Bean
  OutboxRelay outboxRelay(
      OutboxStore store, EventSender sender, Clock clock, MessagingProperties properties) {
    return new OutboxRelay(store, sender, clock, properties.relayBatchSize());
  }

  @Bean
  ApplyPriceUpdate applyPriceUpdate(
      SecurityPrices prices,
      SecurityPositions positions,
      Outbox outbox,
      DomainEvents events,
      Clock clock,
      RevaluationRequests requests,
      ObjectProvider<RevaluationLauncher> batch,
      @Value("${ledger.revaluation.inline-limit:1000}") long inlineLimit) {
    // Above the limit the update leaves the revaluation to the batch, which exists only when batch
    // is on. Without it, a security of any size is revalued inline, as before stage 11.
    return batch.getIfAvailable() == null
        ? new ApplyPriceUpdate(prices, positions, outbox, events, clock)
        : new ApplyPriceUpdate(prices, positions, outbox, events, clock, inlineLimit, requests);
  }

  @Bean
  @ConditionalOnBean(CachedQuoteSources.class)
  PriceCacheEviction priceCacheEviction(CachedQuoteSources sources) {
    return new PriceCacheEviction(sources);
  }

  @Bean
  IdempotentConsumer idempotentConsumer(ConsumedMessages consumed, Clock clock) {
    return new IdempotentConsumer(consumed, clock);
  }

  @Bean
  NoticeChannel noticeChannel() {
    return new LoggingNoticeChannel();
  }

  @Bean
  MarginCallNotifier marginCallNotifier(IdempotentConsumer guard, NoticeChannel channel) {
    return new MarginCallNotifier(guard, channel);
  }

  @Bean
  DedupPurge dedupPurge(ConsumedMessages consumed, Clock clock, MessagingProperties properties) {
    return new DedupPurge(consumed, clock, properties.dedupTtl());
  }

  @Bean
  MessagingSchedulers messagingSchedulers(
      OutboxRelay relay,
      OutboxStore store,
      DedupPurge purge,
      Clock clock,
      MessagingProperties properties) {
    return new MessagingSchedulers(
        relay,
        store,
        purge,
        clock,
        properties.relayInterval(),
        properties.dedupPurgeInterval(),
        properties.relayEnabled());
  }

  /**
   * Creates the topics and their dead-letter companions if the broker does not have them. A broker
   * that is away at start-up is logged, not fatal: the relay retries on its own and a message waits
   * in the outbox.
   */
  @Bean
  KafkaAdmin ledgerKafkaAdmin(MessagingProperties properties) {
    KafkaAdmin admin = new KafkaAdmin(Map.of("bootstrap.servers", properties.bootstrapServers()));
    admin.setFatalIfBrokerNotAvailable(false);
    return admin;
  }

  @Bean
  KafkaAdmin.NewTopics ledgerTopics(MessagingProperties properties) {
    List<NewTopic> topics = new ArrayList<>();
    for (String topic :
        List.of(Topics.PRICE_UPDATES, Topics.MARGIN_CALLS, Topics.POSITION_EVENTS)) {
      topics.add(new NewTopic(topic, properties.partitions(), (short) 1));
      topics.add(new NewTopic(Topics.deadLetterOf(topic), 1, (short) 1));
    }
    return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "ledger.messaging",
      name = "consumers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  ConcurrentMessageListenerContainer<String, String> priceUpdateContainer(
      EventCodec codec,
      ApplyPriceUpdate apply,
      KafkaTemplate<String, String> kafka,
      MessagingProperties properties) {
    return KafkaConsumers.container(
        consumerSettings(properties, "price-updates"),
        Topics.PRICE_UPDATES,
        new PriceUpdateListener(codec, apply),
        ErrorHandling.build(kafka, retry(properties)));
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "ledger.messaging",
      name = "consumers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  ConcurrentMessageListenerContainer<String, String> marginCallContainer(
      EventCodec codec,
      MarginCallNotifier notifier,
      KafkaTemplate<String, String> kafka,
      MessagingProperties properties) {
    return KafkaConsumers.container(
        consumerSettings(properties, "margin-calls"),
        Topics.MARGIN_CALLS,
        new MarginCallListener(codec, notifier),
        ErrorHandling.build(kafka, retry(properties)));
  }

  private static KafkaConsumers.Settings consumerSettings(
      MessagingProperties properties, String suffix) {
    return new KafkaConsumers.Settings(
        properties.bootstrapServers(),
        properties.consumerGroupPrefix() + "-" + suffix,
        properties.consumerConcurrency(),
        properties.maxPollRecords(),
        properties.maxPollInterval(),
        Duration.ofSeconds(45));
  }

  private static ErrorHandling.Retry retry(MessagingProperties properties) {
    return new ErrorHandling.Retry(
        properties.retryMaxRetries(),
        properties.retryInitialInterval(),
        2.0,
        Duration.ofSeconds(5));
  }

  @Bean
  PublishPriceUpdate publishPriceUpdate(EventCodec codec, EventSender sender) {
    return new PublishPriceUpdate(codec, sender);
  }
}
