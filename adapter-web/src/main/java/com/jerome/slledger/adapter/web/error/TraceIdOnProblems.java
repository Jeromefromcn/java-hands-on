package com.jerome.slledger.adapter.web.error;

import com.jerome.slledger.adapter.web.trace.TraceIdFilter;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Puts the request's trace id on every {@link ProblemDetail} written, whichever handler produced
 * it: the ledger's own, or the framework's. One place, so no error path can forget it.
 */
@RestControllerAdvice
public class TraceIdOnProblems implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body instanceof ProblemDetail problem
        && request instanceof ServletServerHttpRequest servlet) {
      problem.setProperty("traceId", TraceIdFilter.of(servlet.getServletRequest()));
    }
    return body;
  }
}
