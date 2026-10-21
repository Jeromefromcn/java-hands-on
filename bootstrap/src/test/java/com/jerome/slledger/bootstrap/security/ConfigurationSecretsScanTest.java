package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No literal secret in anything committed: every configuration and Compose value whose name says it
 * is a password, secret, token or key is empty, an environment placeholder, or an obvious "change
 * me"; and no file holds a private key. This is the check that the project's "env vars only" rule
 * is not just a habit.
 */
class ConfigurationSecretsScanTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  /**
   * A key whose name says it holds a secret: it contains password, secret, credential or a key
   * word, or ends in "token" (so {@code access.token.claim} is not one), then {@code :} or {@code
   * =} and the value.
   */
  private static final Pattern SECRET_ASSIGNMENT =
      Pattern.compile(
          "(?i)^\\s*[\"']?([\\w.-]*(?:password|passwd|secret|api[-_]?key|credential|private[-_]?key)[\\w.-]*"
              + "|[\\w.-]*token)[\"']?\\s*[:=]\\s*(.*?)\\s*,?\\s*$");

  private static final Pattern PLACEHOLDER =
      Pattern.compile("^[\"']?(\\$\\{[^}]+\\}|change-me|<[^>]*>|)[\"']?$");

  /**
   * Stage 14: a value computed when it runs, not written down: a shell command substitution ({@code
   * token=$(kc-token.sh ops1)}) or a GitHub Actions expression ({@code ${{ secrets.GITHUB_TOKEN
   * }}}). What it evaluates to is never in the file.
   */
  private static final Pattern RUNTIME_VALUE =
      Pattern.compile("^[\"']?(\\$\\(.*|\\$\\{\\{.*\\}\\})$");

  /** Stage 14: GitHub Actions' {@code id-token} permission scope, a grant and not a credential. */
  private static final Pattern WORKFLOW_PERMISSION =
      Pattern.compile("(?i)id-token:\\s*(read|write|none)");

  /** A key that names where a secret is (a file, a URL), not what it is. */
  private static final Pattern REFERENCE_KEY =
      Pattern.compile("(?i).*(file|path|location|url|uri)$");

  private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

  private static boolean configuration(Path path) {
    String name = path.getFileName().toString();
    String relative = ROOT.relativize(path).toString();
    return !relative.contains("/target/")
        && !relative.contains("/.git/")
        && !relative.contains("/.idea/")
        && !relative.contains("/node_modules/")
        && !relative.startsWith("docs/evidence")
        && (name.endsWith(".yml")
            || name.endsWith(".yaml")
            || name.endsWith(".properties")
            || name.endsWith(".json")
            || name.endsWith(".sh")
            || name.endsWith(".pem")
            || name.endsWith(".example")
            || name.equals(".env"));
  }

  /**
   * Every file git would commit: tracked, or untracked and not ignored. infra/.env holds this
   * machine's real values and is ignored, so it is not a file the scan is about; it never leaves.
   */
  private static List<Path> files() throws IOException {
    Process git =
        new ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z")
            .directory(ROOT.toFile())
            .redirectErrorStream(false)
            .start();
    String listing = new String(git.getInputStream().readAllBytes());
    try {
      if (git.waitFor() != 0) {
        throw new IOException("git ls-files failed");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
    List<Path> files = new ArrayList<>();
    for (String name : listing.split("\0")) {
      Path path = ROOT.resolve(name);
      if (!name.isEmpty() && Files.isRegularFile(path)) {
        files.add(path);
      }
    }
    return files;
  }

  @Test
  @DisplayName("every secret-named value in committed configuration is a placeholder")
  void noLiteralSecretValues() throws IOException {
    List<String> offenders = new ArrayList<>();
    List<Path> files = files();
    for (Path file : files.stream().filter(ConfigurationSecretsScanTest::configuration).toList()) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        Matcher m = SECRET_ASSIGNMENT.matcher(lines.get(i));
        if (m.matches()
            && !REFERENCE_KEY.matcher(m.group(1)).matches()
            && !PLACEHOLDER.matcher(m.group(2)).matches()
            && !RUNTIME_VALUE.matcher(m.group(2)).matches()
            && !WORKFLOW_PERMISSION.matcher(lines.get(i).trim()).matches()
            && !lines.get(i).trim().startsWith("#")
            // A key whose value is a nested block (YAML) or a reference to another property.
            && !m.group(2).startsWith("{")
            && !m.group(2).startsWith("[")) {
          offenders.add(ROOT.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).trim());
        }
      }
    }

    assertThat(files)
        .as("the scan found the configuration it is meant to scan")
        .hasSizeGreaterThan(8);
    assertThat(offenders).as("literal secret values").isEmpty();
  }

  @Test
  @DisplayName("the realm import carries no password value, only environment placeholders")
  void realmImportHasNoPasswords() throws IOException {
    Path realm = ROOT.resolve("infra/keycloak/realm-ledger.json");
    if (!Files.exists(realm)) {
      return;
    }
    Matcher values =
        Pattern.compile("\"value\"\\s*:\\s*\"([^\"]*)\"").matcher(Files.readString(realm));
    int found = 0;
    while (values.find()) {
      found++;
      assertThat(values.group(1)).startsWith("${");
    }
    assertThat(found).as("password values in the realm file").isGreaterThan(0);
  }

  @Test
  @DisplayName("no file in the repository holds a private key")
  void noPrivateKeys() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path file : files()) {
      String name = file.getFileName().toString();
      if (name.endsWith("ConfigurationSecretsScanTest.java")
          || !name.matches(".*\\.(java|yml|yaml|json|properties|sh|md|pem|txt|xml|sql)$")) {
        continue;
      }
      if (PRIVATE_KEY.matcher(Files.readString(file)).find()) {
        offenders.add(ROOT.relativize(file).toString());
      }
    }

    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("the scan itself: it flags a literal secret and passes a placeholder")
  void theScanCatchesWhatItIsFor() {
    assertThat(SECRET_ASSIGNMENT.matcher("  password: hunter2").matches()).isTrue();
    assertThat(PLACEHOLDER.matcher("hunter2").matches()).isFalse();
    assertThat(PLACEHOLDER.matcher("${LEDGER_DB_PASSWORD}").matches()).isTrue();
    assertThat(PLACEHOLDER.matcher("${REDIS_PASSWORD:?set REDIS_PASSWORD in infra/.env}").matches())
        .isTrue();
    assertThat(PLACEHOLDER.matcher("change-me").matches()).isTrue();
    assertThat(PRIVATE_KEY.matcher("-----BEGIN RSA PRIVATE KEY-----").find()).isTrue();
  }
}
