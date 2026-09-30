package com.jerome.slledger.batch.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * Reads a table in pages by key, and saves the key of the last item it handed out in the step's
 * execution context after every chunk.
 *
 * <p>The saved key is what a restart resumes from: {@link #open} seeks to it. Nothing is held open
 * between chunks (no cursor, no connection), so a restart, or a pool that recycled the connection,
 * cannot lose its place, and a row inserted or changed while the job was down cannot make the
 * resumed run skip or repeat a row that sorts after the key.
 *
 * <p>Spring Batch calls {@link #update} inside the chunk's transaction and persists the context
 * with it, so the saved key and the chunk's writes commit together or not at all. That is the
 * property that makes "resume from the last committed chunk" true.
 *
 * <p>Not thread-safe, on purpose: one instance belongs to one step execution (it is step-scoped),
 * so each partition has its own.
 *
 * @param <T> the item; {@code keyOf} extracts the ordering key
 */
public final class KeysetReader<T> implements ItemStreamReader<T> {

  private final String name;
  private final int pageSize;
  private final Function<Optional<String>, List<T>> page;
  private final Function<T, String> keyOf;
  private final Deque<T> buffer = new ArrayDeque<>();

  private Optional<String> lastRead = Optional.empty();
  private boolean exhausted;

  /**
   * @param name prefix of the context key, unique among the step's streams
   * @param pageSize rows per query; a page shorter than this is the last
   * @param page the rows after a key (the first rows when empty), in key order
   */
  public KeysetReader(
      String name,
      int pageSize,
      Function<Optional<String>, List<T>> page,
      Function<T, String> keyOf) {
    if (pageSize < 1) {
      throw new IllegalArgumentException("pageSize must be positive, was " + pageSize);
    }
    this.name = Objects.requireNonNull(name, "name");
    this.pageSize = pageSize;
    this.page = Objects.requireNonNull(page, "page");
    this.keyOf = Objects.requireNonNull(keyOf, "keyOf");
  }

  private String contextKey() {
    return name + ".lastKey";
  }

  @Override
  public void open(ExecutionContext context) {
    buffer.clear();
    exhausted = false;
    lastRead =
        context.containsKey(contextKey())
            ? Optional.of(context.getString(contextKey()))
            : Optional.empty();
  }

  @Override
  public T read() {
    if (buffer.isEmpty() && !exhausted) {
      // Every fetched item has been handed out, so the last one read is where the next page starts.
      List<T> next = page.apply(lastRead);
      buffer.addAll(next);
      exhausted = next.size() < pageSize;
    }
    T item = buffer.poll();
    if (item != null) {
      lastRead = Optional.of(keyOf.apply(item));
    }
    return item;
  }

  @Override
  public void update(ExecutionContext context) {
    lastRead.ifPresent(key -> context.putString(contextKey(), key));
  }

  @Override
  public void close() {
    buffer.clear();
  }
}
