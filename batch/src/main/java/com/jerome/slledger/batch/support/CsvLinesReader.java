package com.jerome.slledger.batch.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * Reads the lines of a text file after a header, handing out each with its line number, and saves
 * in the step's execution context how many lines it has handed out, so a restart skips those and
 * reads on.
 *
 * <p>It hands out the raw line and does not parse it: a line that cannot be parsed is then a
 * failure of the processor, which the step can skip, and not of the reader, where a skipped read
 * would lose the line's identity. Line numbers count the header as line 1.
 */
public final class CsvLinesReader implements ItemStreamReader<CsvLinesReader.Line> {

  /** One line of the file and where it was. */
  public record Line(int number, String text) {}

  private final String name;
  private final Path file;
  private BufferedReader reader;
  private int number;

  public CsvLinesReader(String name, Path file) {
    this.name = name;
    this.file = file;
  }

  private String contextKey() {
    return name + ".lines";
  }

  @Override
  public void open(ExecutionContext context) {
    try {
      reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
      reader.readLine(); // the header
      number = 1;
      int handedOut = context.getInt(contextKey(), 0);
      for (int i = 0; i < handedOut; i++) {
        if (reader.readLine() == null) {
          break;
        }
        number++;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot open " + file, e);
    }
  }

  @Override
  public Line read() {
    try {
      String text;
      do {
        text = reader.readLine();
        if (text == null) {
          return null;
        }
        number++;
      } while (text.isBlank());
      return new Line(number, text);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  @Override
  public void update(ExecutionContext context) {
    // Lines after the header that have been consumed, blank ones included: the count a reopen
    // skips.
    context.putInt(contextKey(), number - 1);
  }

  @Override
  public void close() {
    try {
      if (reader != null) {
        reader.close();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
