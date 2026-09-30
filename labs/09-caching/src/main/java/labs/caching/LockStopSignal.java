package labs.caching;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * A lock holder that is stopped, not slow. A child JVM takes the lock and starts "working"; the
 * parent stops it with {@code kill -STOP}, so no thread of it runs, its watchdog included, and
 * carries on as a second holder. Then the child is continued and finishes its write.
 *
 * <p>Five scenarios, one Redis, one line of evidence each:
 *
 * <ol>
 *   <li>Redisson, the holder is slow but alive: the watchdog renews, the second holder waits.
 *   <li>Redisson, the holder is stopped: the watchdog stops with it, the lease runs out, the second
 *       holder gets the lock, and the resumed child writes anyway.
 *   <li>Hand-written lock, the holder is stopped: the same.
 *   <li>Hand-written lock with a fencing token, the holder is stopped: the resumed child's write is
 *       refused.
 *   <li>Hand-written lock, the holder is slow but alive: no watchdog, so the lease runs out under a
 *       live holder. This is the case Redisson's watchdog exists for.
 * </ol>
 *
 * Linux only: it shells out to {@code kill}.
 */
public final class LockStopSignal {

  private static final long LEASE_MILLIS = 3_000;

  private record Scenario(
      String name, String mode, long workMillis, boolean stopChild, String expectation) {}

  private record Event(long atMillis, String who, String what) {}

  private record Result(Scenario scenario, List<Event> events, long total, boolean handedOver) {}

  private LockStopSignal() {}

  public static void main(String[] args) throws Exception {
    List<Scenario> scenarios =
        List.of(
            new Scenario(
                "1. Redisson, holder slow but alive (9 s of work)",
                "redisson",
                9_000,
                false,
                "watchdog renews the lease; second holder waits"),
            new Scenario(
                "2. Redisson, holder stopped with SIGSTOP",
                "redisson",
                6_000,
                true,
                "watchdog is stopped too; lease runs out; two holders"),
            new Scenario(
                "3. Hand-written lock, holder stopped with SIGSTOP",
                "handwritten",
                6_000,
                true,
                "lease runs out; two holders"),
            new Scenario(
                "4. Hand-written lock + fencing token, holder stopped with SIGSTOP",
                "fenced",
                6_000,
                true,
                "lease runs out; stale write refused"),
            new Scenario(
                "5. Hand-written lock, holder slow but alive (6 s of work)",
                "handwritten",
                6_000,
                false,
                "no watchdog; lease runs out under a live holder"));

    List<Result> results = new ArrayList<>();
    try (RedisContainer redis = new RedisContainer()) {
      for (Scenario scenario : scenarios) {
        System.out.println("running: " + scenario.name());
        results.add(run(redis, scenario));
      }
    }

    StringBuilder out = new StringBuilder();
    out.append("Stage 9 lab: a lock holder that is stopped, and one that is only slow\n");
    out.append("Environment: ").append(Env.describe()).append('\n');
    out.append(
        """
        Redis 7.4 (Testcontainers), lease / watchdog timeout %d ms. The holder is a child JVM; a
        stop is `kill -STOP` on its pid, a resume is `kill -CONT`. Times are milliseconds since the
        scenario began, taken by the parent. "Handed over" means another holder acquired the lock
        before the child wrote. The write is `total = total + 1`; one round should add once per
        holder that legitimately ran.

        """
            .formatted(LEASE_MILLIS));
    for (Result result : results) {
      out.append("== ").append(result.scenario().name()).append('\n');
      out.append("   expected: ").append(result.scenario().expectation()).append('\n');
      for (Event event : result.events()) {
        out.append("   %6d ms  %-7s %s%n".formatted(event.atMillis(), event.who(), event.what()));
      }
      out.append(
          "   => lock handed to another holder before the child wrote: %s; total = %d%n%n"
              .formatted(result.handedOver() ? "yes" : "no", result.total()));
    }
    Path target = Path.of("target", "evidence");
    Files.createDirectories(target);
    Files.writeString(target.resolve("09-watchdog.txt"), out.toString());
    System.out.println(out);
  }

  private static Result run(RedisContainer redis, Scenario scenario) throws Exception {
    try (HandLock admin = new HandLock(redis.host(), redis.port())) {
      flush(redis);
      List<Event> events = new CopyOnWriteArrayList<>();
      long start = System.nanoTime();

      String jar =
          Path.of(LockStopSignal.class.getProtectionDomain().getCodeSource().getLocation().toURI())
              .toString();
      Process child =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  jar,
                  "labs.caching.Holder",
                  scenario.mode(),
                  redis.host(),
                  Integer.toString(redis.port()),
                  Long.toString(LEASE_MILLIS),
                  Long.toString(scenario.workMillis()))
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      Thread reader =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try (BufferedReader lines =
                        new BufferedReader(
                            new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                      String line;
                      while ((line = lines.readLine()) != null) {
                        events.add(new Event(since(start), "child", line));
                      }
                    } catch (IOException ignored) {
                      // the child is gone
                    }
                  });

      awaitLine(events, "ACQUIRED", 60_000);
      if (scenario.stopChild()) {
        Thread.sleep(1_000);
        signal(child, "STOP");
        events.add(new Event(since(start), "parent", "kill -STOP the child"));
      }

      long acquiredAt = contend(redis, scenario, events, start);

      if (scenario.stopChild()) {
        Thread.sleep(500);
        signal(child, "CONT");
        events.add(new Event(since(start), "parent", "kill -CONT the child"));
      }
      child.waitFor(60, TimeUnit.SECONDS);
      reader.join(5_000);

      long childWrote =
          events.stream()
              .filter(e -> e.who().equals("child") && e.what().equals("WRITING"))
              .mapToLong(Event::atMillis)
              .findFirst()
              .orElse(Long.MAX_VALUE);
      boolean handedOver = acquiredAt >= 0 && acquiredAt < childWrote;
      List<Event> ordered = new ArrayList<>(events);
      ordered.sort((a, b) -> Long.compare(a.atMillis(), b.atMillis()));
      return new Result(scenario, ordered, admin.total(Holder.TOTAL), handedOver);
    }
  }

  /** The parent as a second holder: polls for the lock, writes once, releases. */
  private static long contend(
      RedisContainer redis, Scenario scenario, List<Event> events, long start) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    if (scenario.mode().equals("redisson")) {
      Config config = new Config();
      config.useSingleServer().setAddress("redis://%s:%d".formatted(redis.host(), redis.port()));
      RedissonClient client = Redisson.create(config);
      try {
        RLock lock = client.getLock(Holder.RESOURCE);
        while (System.nanoTime() < deadline) {
          if (lock.tryLock(0, 30, TimeUnit.SECONDS)) {
            long at = since(start);
            events.add(new Event(at, "parent", "ACQUIRED the lock"));
            client.getAtomicLong(Holder.TOTAL).incrementAndGet();
            events.add(new Event(since(start), "parent", "WROTE"));
            lock.unlock();
            return at;
          }
          Thread.sleep(100);
        }
      } finally {
        client.shutdown();
      }
    } else {
      try (HandLock lock = new HandLock(redis.host(), redis.port())) {
        while (System.nanoTime() < deadline) {
          long token = lock.tryAcquire(Holder.RESOURCE, 30_000);
          if (token != 0) {
            long at = since(start);
            events.add(new Event(at, "parent", "ACQUIRED the lock, token=" + token));
            if (scenario.mode().equals("fenced")) {
              boolean admitted = lock.fencedAdd(Holder.TOTAL, Holder.FENCE_SEEN, token);
              events.add(new Event(since(start), "parent", admitted ? "WROTE" : "REFUSED"));
            } else {
              lock.unfencedAdd(Holder.TOTAL);
              events.add(new Event(since(start), "parent", "WROTE"));
            }
            lock.release(Holder.RESOURCE);
            return at;
          }
          Thread.sleep(100);
        }
      }
    }
    events.add(new Event(since(start), "parent", "never acquired the lock in 25 s"));
    return -1;
  }

  private static void awaitLine(List<Event> events, String prefix, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (events.stream().anyMatch(e -> e.what().startsWith(prefix))) {
        return;
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException("child never printed " + prefix);
  }

  private static void signal(Process child, String name) throws Exception {
    new ProcessBuilder("kill", "-" + name, Long.toString(child.pid()))
        .inheritIO()
        .start()
        .waitFor();
  }

  private static void flush(RedisContainer redis) {
    try (HandLock connection = new HandLock(redis.host(), redis.port())) {
      connection.flushAll();
    }
  }

  private static long since(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }
}
