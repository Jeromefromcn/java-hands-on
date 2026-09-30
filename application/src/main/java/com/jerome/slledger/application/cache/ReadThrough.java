package com.jerome.slledger.application.cache;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * A read-through cache over one or more tiers, nearest first.
 *
 * <ul>
 *   <li>A hit in a farther tier is copied into the nearer ones.
 *   <li>A load is single-flight per key: callers that arrive while a load is running wait for its
 *       answer instead of loading again. If that load fails, each waiter loads for itself; one
 *       failure is not served to a crowd.
 *   <li>A loader that returns {@code Optional.empty()} stores an {@link Entry.Absent} with the
 *       tier's negative TTL, so a key the source does not know is asked about once per TTL, not
 *       once per request.
 *   <li>A loader that throws stores nothing.
 *   <li>A tier that throws is counted and skipped: a cache outage slows a read, never fails it.
 *   <li>{@link #evict} removes the key from every tier, and a load already running for that key
 *       still answers its callers but no longer stores its result.
 * </ul>
 *
 * <p>With a {@link Rebuild}, the load also takes a lock that spans instances, so a hot key that
 * expires everywhere at once is rebuilt by one instance and read from the shared tier by the rest.
 */
public final class ReadThrough<K, V> {

  /** A tier and how long it keeps what it is given. */
  public record Layer<K, V>(CacheTier<K, V> tier, TtlPolicy ttl) {}

  /**
   * Cross-instance mutual exclusion for a rebuild. A caller that cannot get the lock runs {@code
   * pause}, then looks in the tiers again and tries the lock again, up to {@code polls} times, and
   * then loads anyway: the lock is an optimisation and must never make a read depend on it.
   */
  public record Rebuild<K>(
      DistributedLock lock, Function<K, String> resource, Duration ttl, int polls, Runnable pause) {

    public Rebuild {
      Objects.requireNonNull(lock, "lock");
      Objects.requireNonNull(resource, "resource");
      Objects.requireNonNull(ttl, "ttl");
      Objects.requireNonNull(pause, "pause");
      if (polls < 0) {
        throw new IllegalArgumentException("polls must not be negative, was " + polls);
      }
    }
  }

  private record Outcome<V>(Optional<V> value, boolean store) {}

  private static final class Flight<V> {

    final CompletableFuture<Optional<V>> result = new CompletableFuture<>();
    private boolean stale;

    synchronized void markStale() {
      stale = true;
    }

    /** Runs {@code store} unless an evict arrived first; an evict waits for a store in progress. */
    synchronized void storeUnlessStale(Runnable store) {
      if (!stale) {
        store.run();
      }
    }
  }

  private final List<Layer<K, V>> layers;
  private final Function<K, Optional<V>> loader;
  private final Random random;
  private final Rebuild<K> rebuild;
  private final CacheCounters counters = new CacheCounters();
  private final ConcurrentHashMap<K, Flight<V>> flights = new ConcurrentHashMap<>();

  public ReadThrough(
      List<Layer<K, V>> layers,
      Function<K, Optional<V>> loader,
      Random random,
      Rebuild<K> rebuild) {
    if (layers.isEmpty()) {
      throw new IllegalArgumentException("A cache needs at least one tier");
    }
    this.layers = List.copyOf(layers);
    this.loader = Objects.requireNonNull(loader, "loader");
    this.random = Objects.requireNonNull(random, "random");
    this.rebuild = rebuild;
  }

  public ReadThrough(List<Layer<K, V>> layers, Function<K, Optional<V>> loader, Random random) {
    this(layers, loader, random, null);
  }

  /** The value, or empty when the source has no such key. */
  public Optional<V> get(K key) {
    boolean counted = false;
    while (true) {
      Optional<Entry<V>> cached = readTiers(key);
      if (cached.isPresent()) {
        counters.hit();
        return asOptional(cached.get());
      }
      if (!counted) {
        counters.miss();
        counted = true;
      }
      Flight<V> mine = new Flight<>();
      Flight<V> running = flights.putIfAbsent(key, mine);
      if (running == null) {
        return lead(key, mine);
      }
      try {
        return running.result.get();
      } catch (ExecutionException failedLoad) {
        // Not this caller's failure to inherit: go round and load for itself.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for a load of " + key, e);
      }
    }
  }

  public void evict(K key) {
    Flight<V> running = flights.get(key);
    if (running != null) {
      running.markStale();
    }
    for (Layer<K, V> layer : layers) {
      try {
        layer.tier().evict(key);
      } catch (RuntimeException e) {
        counters.tierError();
      }
    }
  }

  public CacheCounters counters() {
    return counters;
  }

  private Optional<V> lead(K key, Flight<V> flight) {
    try {
      Outcome<V> outcome = produce(key);
      if (outcome.store()) {
        flight.storeUnlessStale(() -> store(key, outcome.value()));
      }
      flight.result.complete(outcome.value());
      return outcome.value();
    } catch (RuntimeException | Error failure) {
      counters.loadFailure();
      flight.result.completeExceptionally(failure);
      throw failure;
    } finally {
      flights.remove(key, flight);
    }
  }

  private Outcome<V> produce(K key) {
    if (rebuild == null) {
      return load(key);
    }
    for (int attempt = 0; ; attempt++) {
      Optional<DistributedLock.Lease> lease = tryLock(key);
      if (lease.isPresent()) {
        try {
          // Another instance may have stored it while this one was getting the lock.
          Optional<Entry<V>> already = readTiers(key);
          return already.isPresent() ? new Outcome<>(asOptional(already.get()), false) : load(key);
        } finally {
          release(lease.get());
        }
      }
      if (attempt >= rebuild.polls()) {
        break;
      }
      // Someone else is rebuilding. Wait for their result, and take over if they fail: each pass
      // tries the lock again, so a holder that died or threw does not strand the others.
      rebuild.pause().run();
      Optional<Entry<V>> already = readTiers(key);
      if (already.isPresent()) {
        return new Outcome<>(asOptional(already.get()), false);
      }
    }
    counters.lockFallback();
    return load(key);
  }

  private Optional<DistributedLock.Lease> tryLock(K key) {
    try {
      return rebuild.lock().tryAcquire(rebuild.resource().apply(key), rebuild.ttl());
    } catch (RuntimeException e) {
      counters.tierError();
      return Optional.empty();
    }
  }

  private void release(DistributedLock.Lease lease) {
    try {
      lease.release();
    } catch (RuntimeException e) {
      counters.tierError();
    }
  }

  private Outcome<V> load(K key) {
    counters.load();
    return new Outcome<>(loader.apply(key), true);
  }

  private Optional<Entry<V>> readTiers(K key) {
    for (int i = 0; i < layers.size(); i++) {
      Optional<Entry<V>> found;
      try {
        found = layers.get(i).tier().get(key);
      } catch (RuntimeException e) {
        counters.tierError();
        continue;
      }
      if (found.isPresent()) {
        for (int nearer = 0; nearer < i; nearer++) {
          put(layers.get(nearer), key, found.get());
        }
        return found;
      }
    }
    return Optional.empty();
  }

  private void store(K key, Optional<V> value) {
    Entry<V> entry = value.<Entry<V>>map(Entry::present).orElseGet(Entry::absent);
    for (Layer<K, V> layer : layers) {
      put(layer, key, entry);
    }
  }

  private void put(Layer<K, V> layer, K key, Entry<V> entry) {
    Duration ttl =
        entry instanceof Entry.Present ? layer.ttl().ttlFor(random) : layer.ttl().negativeTtl();
    try {
      layer.tier().put(key, entry, ttl);
    } catch (RuntimeException e) {
      counters.tierError();
    }
  }

  private static <V> Optional<V> asOptional(Entry<V> entry) {
    return switch (entry) {
      case Entry.Present<V> present -> Optional.of(present.value());
      case Entry.Absent<V> absent -> Optional.empty();
    };
  }
}
