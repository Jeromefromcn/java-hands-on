package com.jerome.slledger.adapter.web.layers;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Test-only: one probe in each of the three layers a request passes, each recording what it can
 * see. Only {@code TraceIdFilter} of these exists in production; the rest exist to be compared with
 * it. See docs/decisions/07-web.md.
 */
@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy
public class LayerProbes {

  static final List<String> LOG = Collections.synchronizedList(new ArrayList<>());

  /** Right after TraceIdFilter, so the trace id is already in place. */
  @Bean
  FilterRegistrationBean<Filter> recordingFilter() {
    Filter filter =
        (request, response, chain) -> {
          HttpServletRequest http = (HttpServletRequest) request;
          LOG.add(
              "filter      > %s %s (raw request: headers %s)"
                  .formatted(
                      http.getMethod(),
                      http.getRequestURI(),
                      Collections.list(http.getHeaderNames())));
          try {
            chain.doFilter(request, response);
            LOG.add(
                "filter      < status %d, committed %s"
                    .formatted(
                        ((HttpServletResponse) response).getStatus(), response.isCommitted()));
          } catch (Exception e) {
            LOG.add("filter      ! " + e.getClass().getSimpleName());
            throw e;
          }
        };
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }

  @Bean
  WebMvcConfigurer recordingInterceptor() {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                LOG.add("interceptor > preHandle, handler " + describe(handler));
                return true;
              }

              @Override
              public void postHandle(
                  HttpServletRequest request,
                  HttpServletResponse response,
                  Object handler,
                  ModelAndView modelAndView) {
                LOG.add(
                    "interceptor   postHandle, response committed %s"
                        .formatted(response.isCommitted()));
              }

              @Override
              public void afterCompletion(
                  HttpServletRequest request,
                  HttpServletResponse response,
                  Object handler,
                  Exception ex) {
                LOG.add(
                    "interceptor < afterCompletion, exception %s, status %d"
                        .formatted(
                            ex == null ? "null" : ex.getClass().getSimpleName(),
                            response.getStatus()));
              }
            });
      }
    };
  }

  @Bean
  RecordingAspect recordingAspect() {
    return new RecordingAspect();
  }

  @Aspect
  static class RecordingAspect {

    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    Object around(ProceedingJoinPoint call) throws Throwable {
      LOG.add(
          "aspect      > %s args %s"
              .formatted(call.getSignature().toShortString(), Arrays.toString(call.getArgs())));
      try {
        Object result = call.proceed();
        LOG.add("aspect      < returned " + result);
        return result;
      } catch (Throwable t) {
        LOG.add("aspect      ! threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        throw t;
      }
    }
  }

  private static String describe(Object handler) {
    return handler instanceof HandlerMethod method
        ? method.getBeanType().getSimpleName() + "#" + method.getMethod().getName()
        : handler.getClass().getSimpleName();
  }
}
