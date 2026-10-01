package com.jerome.slledger.adapter.web.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * For the tests of this module that are about the filter, the handler or a controller's own
 * behaviour and not about who may call: a chain that lets every request through. Authentication and
 * authorization are tested where the real chain is, in {@code bootstrap}'s integration tests, with
 * real tokens; nothing there uses this.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PermitAll {

  @Bean
  SecurityFilterChain permitAllChain(HttpSecurity http) {
    return http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .build();
  }
}
