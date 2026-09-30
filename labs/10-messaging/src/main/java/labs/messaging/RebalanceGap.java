package labs.messaging;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Three consumers on six partitions, one of them removed, and what the other two notice. Run for the
 * eager (range) and the cooperative-sticky assignor, and for a graceful shutdown (SIGTERM: the member
 * leaves the group) and a crash (SIGKILL: the broker has to time it out). Each member is a separate
 * JVM, because a kill has to be a kill.
 *
 * <p>Measured, per run: how long the partitions the dead member owned went without being processed
 * by anyone; the longest pause on any partition that stayed with a survivor; and how many records
 * were handled twice (the batch the dead member had not committed).
 *
 * <p>Usage: {@code java -cp target/labs.jar labs.messaging.RebalanceGap}. Takes about five minutes.
 */
public final class RebalanceGap {

  private static final int PARTITIONS = 6;
  private static final int SESSION_MS = 10_000;
  private static final String RANGE = "org.apache.kafka.clients.consumer.RangeAssignor";
  private static final String COOPERATIVE = "org.apache.kafka.clients.consumer.CooperativeStickyAssignor";

  /** One line printed by a member: R (record), A (assigned), V (revoked). */
  private record Line(String kind, String member, String detail, long at) {}

  private RebalanceGap() {}

  public static void main(String[] args) throws Exception {
    StringBuilder report = new StringBuilder();
    report.append(Env.describe()).append('\n');
    report.append("Kafka apache/kafka:4.1.0 single broker; 3 consumer JVMs on ").append(PARTITIONS)
        .append(" partitions, ~10 records/s/partition, session.timeout.ms=").append(SESSION_MS)
        .append(", heartbeat ").append(SESSION_MS / 3).append(" ms, manual commit after each poll's batch\n");
    report.append("Member 3 is removed 20 s after all three are consuming; each run observes 45 s after that.\n\n");
    System.out.print(report);

    try (KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0")) {
      kafka.start();
      for (String assignor : List.of(RANGE, COOPERATIVE)) {
        for (boolean crash : List.of(false, true)) {
          String line = run(kafka.getBootstrapServers(), assignor, crash);
          System.out.println(line);
          report.append(line).append('\n');
        }
      }
    }
    report.append("\nReading: 'orphaned' is the time the partitions member 3 owned went with nobody processing them; ")
        .append("'survivor pause' is the longest pause on any partition that stayed with member 1 or 2 (steady state is ~100 ms); ")
        .append("'handled twice' counts records first handled by one member and handled again by another after the removal (an uncommitted batch redelivered). \n");
    Path out = Path.of("target", "evidence", "10-rebalance.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report.toString());
  }

  private static String run(String bootstrap, String assignor, boolean crash) throws Exception {
    String suffix = UUID();
    String topic = "rebalance-" + suffix;
    String group = "group-" + suffix;
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
      admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get(30, TimeUnit.SECONDS);
    }

    AtomicBoolean producing = new AtomicBoolean(true);
    Thread producer =
        Thread.ofPlatform()
            .start(
                () -> {
                  Map<String, Object> config = new HashMap<>();
                  config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                  config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                  config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                  try (KafkaProducer<String, String> p = new KafkaProducer<>(config)) {
                    long n = 0;
                    while (producing.get()) {
                      for (int partition = 0; partition < PARTITIONS; partition++) {
                        p.send(new ProducerRecord<>(topic, partition, "k", "v" + n));
                      }
                      n++;
                      Thread.sleep(100);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });

    List<Line> lines = new CopyOnWriteArrayList<>();
    List<Process> members = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  "-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
                  "labs.messaging.GroupMember",
                  bootstrap,
                  group,
                  topic,
                  assignor,
                  String.valueOf(SESSION_MS),
                  "m" + i)
              .redirectErrorStream(true)
              .start();
      members.add(process);
      Thread.ofPlatform().daemon().start(() -> collect(process, lines));
      Thread.sleep(3_000); // staggered starts: each join is its own rebalance
    }
    // Steady state: all three are consuming.
    Thread.sleep(20_000);

    long killedAt = System.currentTimeMillis();
    Set<String> ownedByDead = partitionsHandledBy("m3", lines, killedAt - 5_000, killedAt);
    Set<String> ownedBySurvivors = new HashSet<>();
    ownedBySurvivors.addAll(partitionsHandledBy("m1", lines, killedAt - 5_000, killedAt));
    ownedBySurvivors.addAll(partitionsHandledBy("m2", lines, killedAt - 5_000, killedAt));
    Process dead = members.get(2);
    if (crash) {
      dead.destroyForcibly(); // SIGKILL
    } else {
      dead.destroy(); // SIGTERM: the shutdown hook closes the consumer, which leaves the group
    }
    Thread.sleep(45_000);
    producing.set(false);
    producer.join();
    for (Process member : members) {
      member.destroyForcibly();
    }
    Thread.sleep(500);

    // --- measure ---
    Map<String, List<Long>> byPartition = new TreeMap<>();
    // Handled twice, counted only for deliveries after the removal: a record first handled by one
    // member and handled again by another once member 3 was gone. Rebalances while the members were
    // still joining also move partitions, and are not what this run is about.
    Map<String, Set<String>> deliveredBefore = new HashMap<>();
    Set<String> handledTwice = new HashSet<>();
    List<Line> ordered = new ArrayList<>(lines);
    ordered.sort((a, b) -> Long.compare(a.at(), b.at()));
    for (Line line : ordered) {
      if (!line.kind().equals("R")) {
        continue;
      }
      String[] detail = line.detail().split(" ");
      String partition = detail[0];
      String offset = detail[1];
      byPartition.computeIfAbsent(partition, k -> new ArrayList<>()).add(line.at());
      String record = partition + ":" + offset;
      Set<String> before = deliveredBefore.computeIfAbsent(record, k -> new HashSet<>());
      if (line.at() >= killedAt && !before.isEmpty() && !before.contains(line.member())) {
        handledTwice.add(record);
      }
      before.add(line.member());
    }
    long orphanedMs = -1;
    for (String partition : ownedByDead) {
      long resumed =
          byPartition.getOrDefault(partition, List.of()).stream().filter(t -> t > killedAt).min(Long::compare).orElse(-1L);
      // Measured from the kill to the first record on the partition after it, the last partition to resume.
      long gap = resumed < 0 ? Long.MAX_VALUE : resumed - killedAt;
      orphanedMs = Math.max(orphanedMs, gap);
    }
    long survivorPause = 0;
    for (String partition : ownedBySurvivors) {
      List<Long> times = new ArrayList<>(byPartition.getOrDefault(partition, List.of()));
      times.sort(Long::compare);
      for (int i = 1; i < times.size(); i++) {
        if (times.get(i) >= killedAt - 2_000) {
          survivorPause = Math.max(survivorPause, times.get(i) - times.get(i - 1));
        }
      }
    }

    return "%-30s %-8s | member 3 owned partitions %s | orphaned partitions idle: %s | survivor pause: %d ms | handled twice: %d"
        .formatted(
            assignor.substring(assignor.lastIndexOf('.') + 1),
            crash ? "SIGKILL" : "SIGTERM",
            ownedByDead.stream().sorted().toList(),
            orphanedMs == Long.MAX_VALUE ? "never (within 45 s)" : orphanedMs + " ms",
            survivorPause,
            handledTwice.size());
  }

  private static Set<String> partitionsHandledBy(String member, List<Line> lines, long from, long to) {
    Set<String> partitions = new HashSet<>();
    for (Line line : lines) {
      if (line.kind().equals("R") && line.member().equals(member) && line.at() >= from && line.at() <= to) {
        partitions.add(line.detail().split(" ")[0]);
      }
    }
    return partitions;
  }

  private static void collect(Process process, List<Line> lines) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String text;
      while ((text = reader.readLine()) != null) {
        String[] parts = text.split(" ");
        if (parts.length >= 4 && (parts[0].equals("R"))) {
          lines.add(new Line("R", parts[1], parts[2] + " " + parts[3], Long.parseLong(parts[4])));
        } else if (parts.length >= 4 && (parts[0].equals("A") || parts[0].equals("V"))) {
          lines.add(new Line(parts[0], parts[1], parts[2], Long.parseLong(parts[3])));
        }
      }
    } catch (IOException ignored) {
      // The process was killed; its output ends.
    }
  }

  private static String UUID() {
    return java.util.UUID.randomUUID().toString().substring(0, 8);
  }

  static Duration seconds(long n) {
    return Duration.ofSeconds(n);
  }
}
