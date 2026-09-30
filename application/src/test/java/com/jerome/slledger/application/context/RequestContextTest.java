package com.jerome.slledger.application.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.context.RequestContext.Snapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class RequestContextTest {

  private static final StringBuilder EVIDENCE =
      new StringBuilder("Stage 08 · section 7 — ThreadLocal in a pool (RequestContextTest)\n\n");

  @AfterAll
  static void writeEvidence() {
    try {
      Path directory = Path.of("target", "evidence");
      Files.createDirectories(directory);
      Files.writeString(directory.resolve("08-threadlocal-bleed.txt"), EVIDENCE.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private final ExecutorService oneThread = Executors.newSingleThreadExecutor();

  @AfterEach
  void tearDown() {
    RequestContext.clear();
    oneThread.shutdownNow();
  }

  @Test
  @DisplayName("a value set by one task and never cleared is read by the next task on that thread")
  void reproducesRequestContextBleedingToNextTask() throws Exception {
    // See docs/decisions/08-concurrency.md
    oneThread.submit(() -> RequestContext.set(new Snapshot("request-A"))).get(10, TimeUnit.SECONDS);

    Optional<Snapshot> seenByRequestB =
        oneThread.submit(RequestContext::current).get(10, TimeUnit.SECONDS);

    assertThat(seenByRequestB).contains(new Snapshot("request-A"));
    EVIDENCE.append(
        "1. One-thread pool. Task A set the context to request-A and did not clear it.\n"
            + "   Task B, a different request, read: "
            + seenByRequestB
            + "\n\n");
  }

  @Test
  @DisplayName("propagating: the task sees its submitter's context and leaves nothing behind")
  void wrappedSubmissionDoesNotBleed() throws Exception {
    RequestContext.set(new Snapshot("request-A"));
    var seenInsideA =
        oneThread
            .submit(RequestContext.propagating(RequestContext::current)::get)
            .get(10, TimeUnit.SECONDS);
    RequestContext.clear();

    Optional<Snapshot> seenByRequestB =
        oneThread.submit(RequestContext::current).get(10, TimeUnit.SECONDS);

    assertThat(seenInsideA).contains(new Snapshot("request-A"));
    assertThat(seenByRequestB).isEmpty();
    EVIDENCE.append(
        "2. The same shape through RequestContext.propagating (capture on submit, restore the\n"
            + "   thread's previous value in finally).\n"
            + "   Task A read: "
            + seenInsideA
            + "\n   Task B read: "
            + seenByRequestB
            + "\n\n");
  }

  @Test
  @DisplayName(
      "propagating restores, not clears: a task run on the submitter's own thread keeps its context")
  void callerRunsKeepsTheSubmittersContext() {
    RequestContext.set(new Snapshot("request-A"));
    var task = RequestContext.propagating(() -> "ran");

    RequestContext.set(new Snapshot("request-A-changed"));
    task.get();

    assertThat(RequestContext.current()).contains(new Snapshot("request-A-changed"));
  }

  @Test
  @DisplayName(
      "an InheritableThreadLocal is copied when a pool creates the thread, so the next task sees a stale value")
  void reproducesInheritableThreadLocalStaleInPool() throws Exception {
    // See docs/decisions/08-concurrency.md
    InheritableThreadLocal<String> inheritable = new InheritableThreadLocal<>();
    inheritable.set("request-A");
    String first =
        oneThread.submit(inheritable::get).get(10, TimeUnit.SECONDS); // creates the thread

    inheritable.set("request-B");
    String second = oneThread.submit(inheritable::get).get(10, TimeUnit.SECONDS); // reuses it

    assertThat(first).isEqualTo("request-A");
    assertThat(second).as("request B's task still sees request A's value").isEqualTo("request-A");
    EVIDENCE.append(
        "3. InheritableThreadLocal set to request-A, then the pool creates its thread (first task).\n"
            + "   First task read: "
            + first
            + "\n   The submitter then set request-B. Second task, on the same thread, read: "
            + second
            + "\n   The value is copied when the thread is created, not when the task is submitted.\n");
  }
}
