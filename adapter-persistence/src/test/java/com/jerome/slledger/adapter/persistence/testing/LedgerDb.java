package com.jerome.slledger.adapter.persistence.testing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts a persistence context on a database of its own, migrated by Flyway from V1.
 *
 * <p>{@code ddl-auto=validate} is not a test convenience: it is the production setting, and every
 * context that starts is a check that the entities and the migrations agree.
 */
public final class LedgerDb {

  private LedgerDb() {}

  public static ConfigurableApplicationContext start(Class<?>... beans) {
    return start(new String[0], beans);
  }

  /** {@code extraProperties} are applied last, so a test can override anything above. */
  public static ConfigurableApplicationContext start(String[] extraProperties, Class<?>... beans) {
    List<String> properties = new ArrayList<>(Arrays.asList(SharedPostgres.freshDatabase()));
    properties.add("spring.jpa.hibernate.ddl-auto=validate");
    properties.add("spring.jpa.open-in-view=false");
    properties.add("spring.jpa.properties.hibernate.generate_statistics=true");
    properties.addAll(Arrays.asList(extraProperties));

    Class<?>[] sources = new Class<?>[beans.length + 1];
    sources[0] = PersistenceTestApplication.class;
    System.arraycopy(beans, 0, sources, 1, beans.length);

    return new SpringApplicationBuilder(sources)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .properties(properties.toArray(String[]::new))
        .run();
  }
}
