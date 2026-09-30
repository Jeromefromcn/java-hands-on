package com.jerome.slledger.adapter.persistence.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a test's measured output to {@code <module>/target/evidence/}. Copied into {@code
 * docs/evidence/} by hand: a test never writes into {@code docs/}.
 */
public final class Evidence {

  private static final Path DIRECTORY = Path.of("target", "evidence");

  private Evidence() {}

  public static void write(String fileName, String content) {
    try {
      Files.createDirectories(DIRECTORY);
      Files.writeString(DIRECTORY.resolve(fileName), content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
