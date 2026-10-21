package com.jerome.slledger.bootstrap.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.security.TestTokens;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which source wins for one property when the repackaged jar is started the way an environment
 * starts the image: a command-line argument, an environment variable, a {@code config/} directory
 * mounted next to it, and the value packaged in the jar. Removed one at a time, highest first.
 *
 * <p>A separate JVM, because an in-process test cannot set an environment variable, and stage 4's
 * precedence test is in-process (docs/evidence/04-config-precedence.txt). The property is {@code
 * info.app.environment}, which each environment sets to name itself and which {@code
 * /actuator/info} serves. See docs/decisions/14-delivery.md.
 */
class ConfigPrecedenceIT {

  private static final Pattern ENVIRONMENT = Pattern.compile("\"environment\":\"([^\"]*)\"");

  @TempDir Path workingDirectory;

  @Test
  void commandLineThenEnvironmentVariableThenMountedFileThenPackaged() throws Exception {
    Path config = Files.createDirectories(workingDirectory.resolve("config"));
    Files.writeString(
        config.resolve("application.yml"), "info:\n  app:\n    environment: mounted-file\n");

    List<String> rows = new ArrayList<>();
    rows.add(run(true, true, true));
    rows.add(run(false, true, true));
    rows.add(run(false, false, true));
    Files.delete(config.resolve("application.yml"));
    rows.add(run(false, false, false));

    assertThat(rows)
        .extracting(row -> row.substring(row.lastIndexOf(' ') + 1))
        .containsExactly("command-line", "environment-variable", "mounted-file", "unnamed");
    Evidence.write(
        "14-config-precedence.txt",
        "One property (info.app.environment), the repackaged jar started as its own process,\n"
            + "sources removed one at a time; the value /actuator/info then reports.\n\n"
            + "argument  env var  config/  -> served\n"
            + String.join("\n", rows)
            + "\n");
  }

  private String run(boolean argument, boolean envVar, boolean file) throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.add("-jar");
    command.add(repackagedJar().toString());
    command.add("--server.port=" + port);
    for (String property : SharedPostgres.freshDatabase()) {
      command.add("--" + property);
    }
    command.addAll(TestTokens.applicationArguments());
    if (argument) {
      command.add("--info.app.environment=command-line");
    }
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(workingDirectory.resolve("app.log").toFile());
    Map<String, String> environment = builder.environment();
    environment.keySet().removeIf(name -> name.startsWith("SPRING_") || name.startsWith("INFO_"));
    environment.remove("JAVA_TOOL_OPTIONS");
    if (envVar) {
      environment.put("INFO_APP_ENVIRONMENT", "environment-variable");
    }
    Process process = builder.start();
    try {
      String served = infoEnvironment(port, process);
      return "%-9s %-8s %-8s -> %s".formatted(yes(argument), yes(envVar), yes(file), served);
    } finally {
      process.destroy();
      process.waitFor();
    }
  }

  private static String yes(boolean present) {
    return present ? "yes" : "-";
  }

  private String infoEnvironment(int port, Process process) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI ready = URI.create("http://localhost:" + port + "/actuator/health/readiness");
    Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
    while (true) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "the application exited:\n" + Files.readString(workingDirectory.resolve("app.log")));
      }
      try {
        if (client
                .send(TestTokens.request(ready).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode()
            == 200) {
          break;
        }
      } catch (IOException notListeningYet) {
        // keep polling
      }
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException("not ready in 120 s");
      }
      Thread.sleep(250);
    }
    String body =
        client
            .send(
                TestTokens.request(URI.create("http://localhost:" + port + "/actuator/info"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .body();
    Matcher matcher = ENVIRONMENT.matcher(body);
    assertThat(matcher.find()).as(body).isTrue();
    return matcher.group(1);
  }

  private static Path repackagedJar() throws IOException {
    try (Stream<Path> files = Files.list(Path.of("target").toAbsolutePath())) {
      return files
          .filter(p -> p.getFileName().toString().matches("bootstrap-.*\\.jar"))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("no repackaged jar in target/"));
    }
  }
}
