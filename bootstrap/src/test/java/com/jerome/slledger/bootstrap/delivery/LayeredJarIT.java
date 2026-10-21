package com.jerome.slledger.bootstrap.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The repackaged jar the image is built from: its layers, in order, and which layer this project's
 * own modules are in. Runs after {@code package}, so it reads the jar the build just made.
 *
 * <p>See docs/decisions/14-delivery.md.
 */
class LayeredJarIT {

  private static final List<String> MODULES =
      List.of(
          "domain",
          "application",
          "adapter-persistence",
          "adapter-web",
          "adapter-messaging",
          "batch");

  @Test
  void ownModulesAreTheirOwnLayerJustUnderTheApplication() throws IOException {
    Map<String, List<String>> layers = layers(repackagedJar());

    assertThat(layers.keySet())
        .containsExactly(
            "dependencies", "spring-boot-loader", "snapshot-dependencies", "ledger", "application");
    assertThat(layers.get("ledger"))
        .map(LayeredJarIT::moduleOf)
        .containsExactlyInAnyOrderElementsOf(MODULES);
    // Nothing of this project anywhere else, and nothing else in the ledger layer.
    layers.forEach(
        (layer, entries) -> {
          if (!layer.equals("ledger")) {
            assertThat(entries).noneMatch(e -> MODULES.contains(moduleOf(e)));
          }
        });

    StringBuilder out =
        new StringBuilder("BOOT-INF/layers.idx of " + repackagedJar().getFileName());
    layers.forEach(
        (layer, entries) ->
            out.append("\n")
                .append(layer)
                .append(": ")
                .append(entries.size())
                .append(" entries")
                .append(layer.equals("ledger") ? " " + entries : ""));
    Evidence.write("14-layers.txt", out.append("\n").toString());
  }

  @Test
  void carriesBuildInfoForTheInfoEndpoint() throws IOException {
    try (JarFile jar = new JarFile(repackagedJar().toFile())) {
      assertThat(jar.getEntry("META-INF/build-info.properties")).isNotNull();
    }
  }

  /** {@code BOOT-INF/lib/domain-0.1.0-SNAPSHOT.jar} to {@code domain}; anything else unchanged. */
  private static String moduleOf(String entry) {
    String name = entry.substring(entry.lastIndexOf('/') + 1);
    return name.replaceFirst("-\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?\\.jar$", "");
  }

  private static Path repackagedJar() throws IOException {
    try (Stream<Path> files = Files.list(Path.of("target"))) {
      return files
          .filter(p -> p.getFileName().toString().matches("bootstrap-.*\\.jar"))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("no repackaged jar in target/"));
    }
  }

  /** The index is YAML-shaped: {@code - "layer":} then {@code - "path"} lines. */
  private static Map<String, List<String>> layers(Path jar) throws IOException {
    Map<String, List<String>> layers = new LinkedHashMap<>();
    try (JarFile file = new JarFile(jar.toFile());
        InputStream index = file.getInputStream(file.getEntry("BOOT-INF/layers.idx"))) {
      List<String> current = null;
      for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        if (line.startsWith("- ")) {
          current = new ArrayList<>();
          layers.put(line.substring(3, line.length() - 2), current);
        } else if (line.startsWith("  - ") && current != null) {
          current.add(line.substring(5, line.length() - 1));
        }
      }
    }
    return layers;
  }
}
