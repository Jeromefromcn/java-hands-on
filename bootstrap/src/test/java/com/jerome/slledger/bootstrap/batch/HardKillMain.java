package com.jerome.slledger.bootstrap.batch;

import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.support.FailurePoint;
import com.jerome.slledger.bootstrap.SlLedgerApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The child process of {@link HardKillRestartIT}: the real application, running the accrual, that
 * stops dead in the middle of a chunk's transaction and waits to be killed.
 *
 * <p>Arguments: JDBC url, user, password, marker file, the chunk to stop in, the business date.
 * When it reaches that chunk it writes the marker file and sleeps: the chunk's transaction is open
 * and nothing of it is committed.
 */
public final class HardKillMain {

  private static final AtomicInteger CHUNKS = new AtomicInteger();

  /** Stops the process inside the chunk it is told to. */
  static class StallInChunk {

    @Bean
    @Primary
    FailurePoint stall() {
      Path marker = Path.of(System.getProperty("hardkill.marker"));
      int stopIn = Integer.getInteger("hardkill.chunk");
      return (step, firstKey) -> {
        if (CHUNKS.incrementAndGet() == stopIn) {
          try {
            Files.writeString(marker, "stalled in the chunk that starts at " + firstKey);
            Thread.sleep(Long.MAX_VALUE);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        }
      };
    }
  }

  private HardKillMain() {}

  public static void main(String[] args) {
    System.setProperty("hardkill.marker", args[3]);
    System.setProperty("hardkill.chunk", args[4]);
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(SlLedgerApplication.class, StallInChunk.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run(
                "--spring.datasource.url=" + args[0],
                "--spring.datasource.username=" + args[1],
                "--spring.datasource.password=" + args[2],
                "--ledger.batch.enabled=true",
                "--ledger.batch.chunk-size=10")) {
      context.getBean(RunAccrual.class).run(LocalDate.parse(args[5]));
    }
  }
}
