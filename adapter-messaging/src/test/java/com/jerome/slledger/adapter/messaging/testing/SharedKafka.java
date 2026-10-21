package com.jerome.slledger.adapter.messaging.testing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka broker per test JVM, started on first use; Ryuk removes it at JVM exit. The same shape
 * as {@code SharedPostgres} and {@code SharedRedis}, and for the same reason.
 *
 * <p>Tests share the broker and give themselves topics of their own ({@link #uniqueTopic}), so no
 * test reads another's messages. Published to {@code bootstrap}'s tests through this module's
 * test-jar.
 */
public final class SharedKafka {

  private static final KafkaContainer CONTAINER = new KafkaContainer("apache/kafka:4.1.0");

  private SharedKafka() {}

  public static synchronized void start() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
  }

  public static synchronized String bootstrapServers() {
    start();
    return CONTAINER.getBootstrapServers();
  }

  /** A topic name no other test uses. */
  public static String uniqueTopic(String stem) {
    return "test." + stem + "." + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * Returns once every partition of {@code name} has a leader. A producer that sends straight after
   * {@code createTopics} can get NOT_LEADER_OR_FOLLOWER, and an idempotent producer that retries
   * through it has been seen to stall on OUT_OF_ORDER_SEQUENCE_NUMBER until its delivery timeout (a
   * full build hit exactly that: a 1,000-record send that never finished).
   */
  private static void awaitLeaders(Admin admin, String name) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        var description =
            admin.describeTopics(List.of(name)).allTopicNames().get(10, TimeUnit.SECONDS).get(name);
        if (description.partitions().stream()
            .allMatch(p -> p.leader() != null && !p.isr().isEmpty())) {
          return;
        }
      } catch (ExecutionException e) {
        // Right after createTopics the broker that answers may not know the topic yet: on a CI
        // runner (stage 14) the first describe failed with UnknownTopicOrPartition. Not yet there
        // is the same as no leader yet; anything else is a real failure.
        if (!(e.getCause() instanceof UnknownTopicOrPartitionException)) {
          throw e;
        }
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("no leaders for " + name + " within 30 s");
  }

  public static void createTopic(String name, int partitions) {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(name, partitions, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
      awaitLeaders(admin, name);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException("Could not create topic " + name, e);
    }
  }

  /** Creates the topic unless it exists; a test that shares a well-known topic name calls this. */
  public static void ensureTopic(String name, int partitions) {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers()))) {
      if (!admin.listTopics().names().get(30, TimeUnit.SECONDS).contains(name)) {
        admin
            .createTopics(List.of(new NewTopic(name, partitions, (short) 1)))
            .all()
            .get(30, TimeUnit.SECONDS);
      }
      awaitLeaders(admin, name);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException("Could not ensure topic " + name, e);
    }
  }

  /** Producer properties for a plain string producer on this broker. */
  public static Properties producerProperties() {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    return properties;
  }

  /** Consumer properties for a string consumer in {@code group}, manual commit, from earliest. */
  public static Properties consumerProperties(String group) {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    properties.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    return properties;
  }

  /**
   * Every record on the topic right now, read by a throwaway consumer with no group, from the
   * beginning of every partition to the end offsets seen at the start of the read.
   */
  public static List<ConsumerRecord<String, String>> readAll(String topic) {
    Properties properties = consumerProperties("readall-" + UUID.randomUUID());
    properties.remove(ConsumerConfig.GROUP_ID_CONFIG);
    List<ConsumerRecord<String, String>> records = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
      List<org.apache.kafka.common.PartitionInfo> infos = consumer.partitionsFor(topic);
      if (infos == null || infos.isEmpty()) {
        return records;
      }
      List<TopicPartition> partitions =
          infos.stream().map(info -> new TopicPartition(topic, info.partition())).toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
      Set<TopicPartition> open = new java.util.HashSet<>();
      ends.forEach(
          (partition, end) -> {
            if (end > 0) {
              open.add(partition);
            }
          });
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (!open.isEmpty() && System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
          records.add(record);
        }
        open.removeIf(partition -> consumer.position(partition) >= ends.get(partition));
      }
    }
    return records;
  }
}
