package com.jerome.slledger.bootstrap.demo.tx.support;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts a demo context against {@link SharedPostgres}, with the schema dropped and recreated. */
public final class TxDemo {

  private TxDemo() {}

  public static ConfigurableApplicationContext start(Class<?>... beans) {
    return start(new String[0], beans);
  }

  /**
   * Starts a non-web context of {@link TxDemoApplication}, {@link TxDemoRows} and {@code beans}.
   * {@code extraProperties} are applied last, so a test can override the pool or anything else.
   */
  public static ConfigurableApplicationContext start(String[] extraProperties, Class<?>... beans) {
    List<String> properties =
        new ArrayList<>(Arrays.asList(SharedPostgres.datasourceProperties("tx_demo")));
    // schema.sql drops and recreates every table, so no context sees what an earlier one wrote.
    properties.add("spring.sql.init.mode=always");
    properties.add("spring.sql.init.schema-locations=classpath:demo/tx/schema.sql");
    // Since stage 6, Flyway and JPA are on this classpath. Stage 5's hand-written schema is not
    // Flyway's schema, so neither may touch it; the tx_demo database is theirs alone.
    properties.add("spring.flyway.enabled=false");
    properties.add(
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration");
    properties.addAll(Arrays.asList(extraProperties));

    Class<?>[] sources = new Class<?>[beans.length + 2];
    sources[0] = TxDemoApplication.class;
    sources[1] = TxDemoRows.class;
    System.arraycopy(beans, 0, sources, 2, beans.length);

    return new SpringApplicationBuilder(sources)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .properties(properties.toArray(String[]::new))
        .run();
  }
}
