package com.jerome.slledger.adapter.web.trace;

import com.jerome.slledger.application.context.RequestContext;
import com.jerome.slledger.application.observability.Tracing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace id: in the MDC for the logs, on the response as {@code X-Trace-Id},
 * and as a request attribute for error bodies.
 *
 * <p>Generated here, in the outermost layer, because a filter is the only layer that sees every
 * request, including those refused before a handler is chosen. An interceptor never sees a request
 * with no handler, and an aspect sees only the controller call. See {@code
 * docs/decisions/07-web.md}.
 *
 * <p>An incoming {@code X-Trace-Id} is ignored: a client-chosen value in the logs is an injection
 * path. Since stage 12 the id is the trace id of the request's server span when a tracer is
 * present, so the response header, the logs and the trace backend name the same trace; an incoming
 * W3C {@code traceparent} is continued by the tracer, which accepts only its fixed hex format.
 * Without a tracer the filter generates an id of the same shape, as before.
 *
 * <p>It runs second, right after Boot's observation filter (which starts the span) and before
 * everything else; running first, it would have no span to read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Trace-Id";
  public static final String MDC_KEY = "traceId";
  public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

  private final ObjectProvider<Tracing> tracing;

  public TraceIdFilter(ObjectProvider<Tracing> tracing) {
    this.tracing = tracing;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId =
        tracing
            .getIfAvailable(() -> Tracing.NONE)
            .currentTraceId()
            .orElseGet(TraceIdFilter::newTraceId);
    request.setAttribute(ATTRIBUTE, traceId);
    response.setHeader(HEADER, traceId);
    MDC.put(MDC_KEY, traceId);
    RequestContext.set(new RequestContext.Snapshot(traceId));
    try {
      chain.doFilter(request, response);
    } finally {
      // Worker threads are pooled: an id left behind would label the next request's logs.
      MDC.remove(MDC_KEY);
      RequestContext.clear();
    }
  }

  /** The id of the request being served, or {@code "unknown"} outside this filter. */
  public static String of(HttpServletRequest request) {
    Object id = request.getAttribute(ATTRIBUTE);
    return id instanceof String s ? s : "unknown";
  }

  private static String newTraceId() {
    byte[] bytes = new byte[16];
    do {
      ThreadLocalRandom.current().nextBytes(bytes);
    } while (isAllZero(bytes)); // all zeros is W3C's "invalid" trace id
    return HexFormat.of().formatHex(bytes);
  }

  private static boolean isAllZero(byte[] bytes) {
    for (byte b : bytes) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
