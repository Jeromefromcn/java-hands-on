package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.adapter.messaging.JsonEventCodec;
import com.jerome.slledger.adapter.messaging.KafkaEventSender;
import com.jerome.slledger.adapter.messaging.KafkaProducers;
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
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

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
      Clock clock) {
    return new ApplyPriceUpdate(prices, positions, outbox, events, clock);
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
  PublishPriceUpdate publishPriceUpdate(EventCodec codec, EventSender sender) {
    return new PublishPriceUpdate(codec, sender);
  }
}
