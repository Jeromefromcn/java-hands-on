package com.jerome.slledger.bootstrap.position;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.bootstrap.SlLedgerApplication;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The real application on a fresh database, for the use-case tests.
 *
 * <p>The datasource goes in as command-line arguments, the highest-precedence source, so no
 * profile's YAML can override it. Extra sources are test-only beans, registered by class: none of
 * them is a {@code @Configuration} or {@code @Component}, so the application's own component scan
 * of this package never picks them up by itself.
 */
public final class LedgerApp {

  private LedgerApp() {}

  public static ConfigurableApplicationContext start(String... extraArgs) {
    return start(List.of(), extraArgs);
  }

  public static ConfigurableApplicationContext start(
      List<Class<?>> extraSources, String... extraArgs) {
    return run(WebApplicationType.NONE, extraSources, extraArgs);
  }

  /** The same application serving HTTP on a random port; see {@link #port}. */
  public static ConfigurableApplicationContext startWeb(String... extraArgs) {
    return startWeb(List.of(), extraArgs);
  }

  public static ConfigurableApplicationContext startWeb(
      List<Class<?>> extraSources, String... extraArgs) {
    List<String> args = new ArrayList<>();
    args.add("--server.port=0");
    args.addAll(Arrays.asList(extraArgs));
    return run(WebApplicationType.SERVLET, extraSources, args.toArray(String[]::new));
  }

  /** The port a context from {@link #startWeb} is listening on. */
  public static int port(ConfigurableApplicationContext context) {
    return ((org.springframework.boot.web.server.context.WebServerApplicationContext) context)
        .getWebServer()
        .getPort();
  }

  private static ConfigurableApplicationContext run(
      WebApplicationType type, List<Class<?>> extraSources, String... extraArgs) {
    List<String> args = new ArrayList<>();
    for (String property : SharedPostgres.freshDatabase()) {
      args.add("--" + property);
    }
    args.addAll(Arrays.asList(extraArgs));
    List<Class<?>> sources = new ArrayList<>();
    sources.add(SlLedgerApplication.class);
    sources.addAll(extraSources);
    return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
        .web(type)
        .bannerMode(Banner.Mode.OFF)
        .run(args.toArray(String[]::new));
  }

  /** Reference data has no use case of its own yet; it is inserted as it would be loaded. */
  public static void referenceData(JdbcClient jdbc) {
    jdbc.sql(
            "INSERT INTO counterparty VALUES"
                + " ('CP-001', 'Synthetic Borrower One', 1000000000.00, 'HKD'),"
                + " ('CP-002', 'Synthetic Borrower Two', 1000000.00, 'USD')")
        .update();
    jdbc.sql(
            "INSERT INTO security VALUES"
                + " ('HK0000000001', 'Synthetic HK Equity', 'HKD', 41.26, 'HKD'),"
                + " ('US0000000001', 'Synthetic US Equity', 'USD', 10.00, 'USD')")
        .update();
  }
}
