package com.jerome.slledger.adapter.web.security;

import com.jerome.slledger.adapter.web.error.Problems;
import com.jerome.slledger.adapter.web.trace.TraceIdFilter;
import com.jerome.slledger.application.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * The edge: who is authenticated, and nothing more. What each role may call is on the handlers
 * ({@code @PreAuthorize}); whose rows is in the queries. This chain only refuses what carries no
 * valid token, and lets the two probes through because an orchestrator cannot send one.
 *
 * <p>Stateless: no session, so no cookie to steal and, with no cookie, no forged cross-site request
 * to defend against; that is the whole reason CSRF protection is off, and it would not be if a
 * session or a basic-auth challenge ever came back. Issuer and audience are checked by the decoder
 * Boot builds from {@code spring.security.oauth2.resourceserver.jwt.*}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

  @Bean
  SecurityFilterChain ledgerChain(
      HttpSecurity http, AuthenticationEntryPoint entryPoint, AccessDeniedHandler denied)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness")
                    .permitAll()
                    // The scrape describes the business (positions opened, margin calls, outbox
                    // age): it is for the scraper's own role, not for any authenticated caller.
                    .requestMatchers("/actuator/prometheus")
                    .hasRole(Role.METRICS.claim())
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            server ->
                server
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter()))
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(denied))
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
        .headers(Customizer.withDefaults());
    return http.build();
  }

  /**
   * The issuer must be the one this deployment names, exactly. Boot does not combine a key source
   * with {@code issuer-uri} without OIDC discovery, so the check is its own bean; Boot adds every
   * {@code OAuth2TokenValidator<Jwt>} bean to the decoder it builds. No default: a missing issuer
   * is a start-up failure, not an API that accepts any.
   */
  @Bean
  OAuth2TokenValidator<Jwt> issuerValidator(@Value("${ledger.security.issuer}") String issuer) {
    return JwtValidators.createDefaultWithIssuer(issuer);
  }

  /** The ledger's roles as {@code ROLE_*} authorities; names it does not define are dropped. */
  static JwtAuthenticationConverter converter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::authorities);
    return converter;
  }

  static Collection<GrantedAuthority> authorities(Jwt jwt) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    for (Role role : JwtCaller.roles(jwt)) {
      authorities.add(new SimpleGrantedAuthority("ROLE_" + role.claim()));
    }
    return authorities;
  }

  /**
   * {@code 401} as a problem. The detail never says why: "expired" and "wrong audience" tell an
   * attacker which part of a forged token to fix. The log line has the reason; the trace id joins
   * them.
   */
  @Bean
  AuthenticationEntryPoint problemEntryPoint(JsonMapper mapper) {
    return (request, response, exception) -> {
      // The reason is for the log: the caller is told nothing, the trace id joins the two. The
      // exception message names the failed check, never the token.
      log.info(
          "401 {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
      write(
          mapper,
          request,
          response,
          Problems.of(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication is required"),
          "Bearer");
    };
  }

  @Bean
  AccessDeniedHandler problemAccessDenied(JsonMapper mapper) {
    return (request, response, exception) ->
        write(
            mapper,
            request,
            response,
            Problems.of(HttpStatus.FORBIDDEN, "forbidden", "The caller may not do this"),
            null);
  }

  private static void write(
      JsonMapper mapper,
      HttpServletRequest request,
      HttpServletResponse response,
      ProblemDetail problem,
      String challenge)
      throws IOException {
    problem.setProperty("traceId", TraceIdFilter.of(request));
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    if (challenge != null) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
    }
    mapper.writeValue(response.getOutputStream(), problem);
  }
}
