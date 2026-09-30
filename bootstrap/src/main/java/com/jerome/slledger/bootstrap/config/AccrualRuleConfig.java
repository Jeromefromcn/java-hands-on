package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.adapter.web.validation.RateBounds;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.money.Rate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccrualRuleProperties.class)
public class AccrualRuleConfig {

  @Bean
  FlatRate defaultAccrualRate(AccrualRuleProperties props) {
    // The lower bound, not the mid-point: it is a value someone actually configured, and a
    // mid-point would need its own rounding decision that nothing here has made.
    return new FlatRate(new Rate(props.rateLowerBound()));
  }

  /** Stage 7: the web layer's {@code @RateRange} checks incoming rates against these bounds. */
  @Bean
  RateBounds rateBounds(AccrualRuleProperties props) {
    return new RateBounds(props.rateLowerBound(), props.rateUpperBound());
  }
}
