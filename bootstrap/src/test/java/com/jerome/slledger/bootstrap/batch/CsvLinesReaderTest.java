package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.batch.support.CsvLinesReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.ExecutionContext;

class CsvLinesReaderTest {

  @TempDir Path directory;

  private Path file(String... lines) throws Exception {
    Path file = directory.resolve("snapshot.csv");
    Files.write(file, List.of(lines));
    return file;
  }

  private static List<CsvLinesReader.Line> readAll(CsvLinesReader reader) {
    List<CsvLinesReader.Line> lines = new ArrayList<>();
    for (CsvLinesReader.Line line = reader.read(); line != null; line = reader.read()) {
      lines.add(line);
    }
    return lines;
  }

  @Test
  @DisplayName("hands out every line after the header with its line number, skipping blank lines")
  void readsLinesWithNumbers() throws Exception {
    CsvLinesReader reader = new CsvLinesReader("s", file("id,qty", "a,1", "", "b,2", "c,3"));
    reader.open(new ExecutionContext());

    List<CsvLinesReader.Line> lines = readAll(reader);

    assertThat(lines)
        .containsExactly(
            new CsvLinesReader.Line(2, "a,1"),
            new CsvLinesReader.Line(4, "b,2"),
            new CsvLinesReader.Line(5, "c,3"));
  }

  @Test
  @DisplayName("a reopened reader resumes after the lines already handed out")
  void resumesFromTheSavedCount() throws Exception {
    Path file = file("id,qty", "a,1", "b,2", "c,3", "d,4");
    CsvLinesReader first = new CsvLinesReader("s", file);
    ExecutionContext context = new ExecutionContext();
    first.open(context);
    first.read();
    first.read();
    first.update(context);
    first.close();

    CsvLinesReader second = new CsvLinesReader("s", file);
    second.open(context);

    assertThat(readAll(second))
        .containsExactly(new CsvLinesReader.Line(4, "c,3"), new CsvLinesReader.Line(5, "d,4"));
  }

  @Test
  @DisplayName("a file with only a header yields nothing")
  void headerOnly() throws Exception {
    CsvLinesReader reader = new CsvLinesReader("s", file("id,qty"));
    reader.open(new ExecutionContext());

    assertThat(reader.read()).isNull();
  }
}
