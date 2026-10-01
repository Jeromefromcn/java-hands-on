package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.adapter.web.client.MarketDataProperties;
import com.jerome.slledger.adapter.web.client.ResilienceProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The timeout budget, checked at start-up, and the database limits it is computed from. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimeoutProperties.class)
public class TimeoutBudgetConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(TimeoutBudgetConfiguration.class);

  @Bean
  TimeoutBudget timeoutBudget(
      TimeoutProperties timeouts,
      MarketDataProperties source,
      MarketDataExecutorProperties executor,
      ResilienceProperties resilience) {
    TimeoutBudget budget = new TimeoutBudget(timeouts, source, executor, resilience);
    LOG.info("Timeout budget:\n{}", budget.table());
    if (timeouts.enforce()) {
      budget.verify();
    } else {
      budget.problems().forEach(problem -> LOG.warn("Timeout budget NOT enforced: {}", problem));
    }
    return budget;
  }

  /**
   * Applies the database limits to the pool: how long a request waits for a connection, and a
   * statement timeout set on every connection as it is opened. Done on the pool bean so that
   * Flyway, JPA, JDBC and Batch all get it, whichever DataSource consumer opens the connection.
   */
  @Bean
  static BeanPostProcessor databaseTimeouts(Environment environment) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource pool) {
          TimeoutProperties timeouts =
              org.springframework.boot.context.properties.bind.Binder.get(environment)
                  .bind("ledger.timeouts", TimeoutProperties.class)
                  .orElseGet(TimeoutProperties::defaults);
          pool.setConnectionTimeout(timeouts.poolAcquire().toMillis());
          pool.setConnectionInitSql("SET statement_timeout = " + timeouts.dbStatement().toMillis());
        }
        return bean;
      }
    };
  }
}
