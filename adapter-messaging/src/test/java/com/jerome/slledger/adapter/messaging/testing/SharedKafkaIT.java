package com.jerome.slledger.adapter.messaging.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class SharedKafkaIT {

  @BeforeAll
  static void start() {
    SharedKafka.start();
  }

  @Test
  @DisplayName("a record produced to a fresh topic is read back with its key")
  void roundTrip() throws Exception {
    String topic = SharedKafka.uniqueTopic("roundtrip");
    SharedKafka.createTopic(topic, 2);

    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(SharedKafka.producerProperties())) {
      producer.send(new ProducerRecord<>(topic, "HK0000000007", "hello")).get();
    }

    List<ConsumerRecord<String, String>> records = SharedKafka.readAll(topic);
    assertThat(records).hasSize(1);
    assertThat(records.get(0).key()).isEqualTo("HK0000000007");
    assertThat(records.get(0).value()).isEqualTo("hello");
  }

  @Test
  @DisplayName("two tests' topics are separate")
  void topicsAreSeparate() {
    String a = SharedKafka.uniqueTopic("a");
    String b = SharedKafka.uniqueTopic("b");
    assertThat(a).isNotEqualTo(b);
  }
}
