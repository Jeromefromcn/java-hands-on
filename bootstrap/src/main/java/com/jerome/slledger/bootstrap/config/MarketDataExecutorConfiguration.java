package com.jerome.slledger.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketDataExecutorProperties.class)
public class MarketDataExecutorConfiguration {

  @Bean(destroyMethod = "close")
  MarketDataPool marketDataPool(MarketDataExecutorProperties props) {
    return new MarketDataPool(props);
  }
}
