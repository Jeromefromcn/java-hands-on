package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.bootstrap.SlLedgerApplication;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A job named on the command line runs once at start-up, as a cron entry would run it. */
@Timeout(300)
class BatchCommandIT {

  @Test
  void accrualNamedOnTheCommandLineRunsAtStartup() {
    String[] db = SharedPostgres.freshDatabase();
    // Migrate and seed through a first context, then start the command on the same database.
    String[] datasource = Arrays.stream(db).map(p -> "--" + p).toArray(String[]::new);
    try (ConfigurableApplicationContext seed =
        new SpringApplicationBuilder(SlLedgerApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run(datasource)) {
      JdbcClient jdbc = seed.getBean(JdbcClient.class);
      com.jerome.slledger.bootstrap.position.LedgerApp.referenceData(jdbc);
      new com.jerome.slledger.adapter.persistence.testing.LedgerFixtures(jdbc)
          .bulkPositions("P", 40, "HK0000000001", "'CP-001'")
          .borrowTradesForAll("P", java.time.LocalDate.of(2026, 9, 1));
    }
    List<String> args = new ArrayList<>(Arrays.asList(datasource));
    args.add("--ledger.batch.enabled=true");
    args.add("--ledger.batch.command.job=accrual");
    args.add("--ledger.batch.command.date=2026-09-30");
    try (ConfigurableApplicationContext app =
        new SpringApplicationBuilder(SlLedgerApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run(args.toArray(String[]::new))) {
      assertThat(
              app.getBean(JdbcClient.class)
                  .sql("SELECT count(*) FROM accrual_entry WHERE business_date = DATE '2026-09-30'")
                  .query(Long.class)
                  .single())
          .isEqualTo(40L);
    }
  }
}
