package com.jerome.slledger.bootstrap.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only market data source on the JDK's own HTTP server: no dependency, and every answer is the
 * test's to choose, including never answering until a latch opens.
 */
final class StubPriceServer implements AutoCloseable {

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, Answer> answers = new ConcurrentHashMap<>();
  private final AtomicInteger arrived = new AtomicInteger();
  private volatile CountDownLatch hang = new CountDownLatch(1);

  sealed interface Answer {}

  record Json(int status, String body) implements Answer {}

  record Hang() implements Answer {}

  private StubPriceServer() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(executor);
    server.createContext("/prices/", this::handle);
    server.start();
  }

  static StubPriceServer start() {
    return new StubPriceServer();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** A well-formed price for {@code isin}. */
  StubPriceServer price(String isin, String amount, String currency) {
    return answer(
        isin,
        new Json(
            200,
            "{\"isin\":\"%s\",\"price\":{\"amount\":\"%s\",\"currency\":\"%s\"}}"
                .formatted(isin, amount, currency)));
  }

  StubPriceServer answer(String isin, Answer answer) {
    answers.put(isin, answer);
    return this;
  }

  /** Requests for {@code isin} wait until {@link #release}, holding their connection open. */
  StubPriceServer hang(String isin) {
    hang = new CountDownLatch(1);
    return answer(isin, new Hang());
  }

  void release() {
    hang.countDown();
  }

  /** How many price requests have arrived so far. */
  int arrived() {
    return arrived.get();
  }

  private void handle(HttpExchange exchange) throws IOException {
    arrived.incrementAndGet();
    String isin = exchange.getRequestURI().getPath().substring("/prices/".length());
    Answer answer = answers.getOrDefault(isin, new Json(404, "{}"));
    try (exchange) {
      if (answer instanceof Hang) {
        try {
          hang.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        answer = new Json(200, "{}");
      }
      Json json = (Json) answer;
      byte[] body = json.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(json.status(), body.length);
      exchange.getResponseBody().write(body);
    } catch (IOException clientGone) {
      // The ledger timed out and closed the connection; nothing to answer.
    }
  }

  @Override
  public void close() {
    release();
    server.stop(0);
    executor.shutdownNow();
  }
}
