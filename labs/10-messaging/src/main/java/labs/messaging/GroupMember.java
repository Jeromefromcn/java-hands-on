package labs.messaging;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * One member of the consumer group, run as a child process of {@link RebalanceGap}. It prints one
 * line per record it handles ({@code R id partition offset epochMillis}) and one per assignment
 * change, and commits after each batch. On SIGTERM it leaves the group (a graceful shutdown); on
 * SIGKILL it does not get the chance, and the broker finds out when the session times out.
 *
 * <p>Arguments: bootstrap servers, group, topic, assignor class, session timeout millis, member id.
 */
public final class GroupMember {

  private GroupMember() {}

  public static void main(String[] args) throws Exception {
    String bootstrap = args[0];
    String group = args[1];
    String topic = args[2];
    String assignor = args[3];
    int sessionMs = Integer.parseInt(args[4]);
    String id = args[5];
    PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, assignor);
    properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, String.valueOf(sessionMs));
    properties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, String.valueOf(Math.max(1000, sessionMs / 3)));

    AtomicBoolean running = new AtomicBoolean(true);
    CountDownLatch closed = new CountDownLatch(1);
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  running.set(false);
                  consumer.wakeup();
                  try {
                    closed.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));

    consumer.subscribe(
        List.of(topic),
        new ConsumerRebalanceListener() {
          @Override
          public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            out.println("A " + id + " " + names(partitions) + " " + System.currentTimeMillis());
          }

          @Override
          public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            out.println("V " + id + " " + names(partitions) + " " + System.currentTimeMillis());
          }
        });
    try {
      while (running.get()) {
        try {
          for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
            out.println("R " + id + " " + record.partition() + " " + record.offset() + " " + System.currentTimeMillis());
          }
          consumer.commitSync();
        } catch (WakeupException stopping) {
          break;
        }
      }
    } finally {
      consumer.close(); // sends LeaveGroup: what a graceful shutdown does and a kill does not
      out.println("CLOSED " + id + " " + System.currentTimeMillis());
      closed.countDown();
    }
  }

  private static String names(Collection<TopicPartition> partitions) {
    return partitions.stream().map(p -> String.valueOf(p.partition())).sorted().toList().toString().replace(" ", "");
  }
}
