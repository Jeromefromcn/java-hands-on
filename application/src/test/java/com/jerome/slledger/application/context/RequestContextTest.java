package com.jerome.slledger.application.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.context.RequestContext.Snapshot;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class RequestContextTest {

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
  }
}
