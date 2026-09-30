package com.jerome.slledger.adapter.web.error;

import com.jerome.slledger.adapter.web.trace.TraceIdFilter;
import com.jerome.slledger.application.position.IdempotencyKeyReusedException;
import com.jerome.slledger.application.position.InvalidQueryException;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.error.DomainException;
import com.jerome.slledger.domain.error.ExcessiveReturnException;
import com.jerome.slledger.domain.error.IllegalTransitionException;
import com.jerome.slledger.domain.error.InsufficientCreditException;
import com.jerome.slledger.domain.error.MalformedIdentifierException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.DatabindException;

/**
 * Every error as an RFC 9457 {@code application/problem+json} body with a stable {@code type}.
 *
 * <p>{@code 404} is for the resource named in the URL. {@code 422} is for a body that is well
 * formed but names something missing or breaks a business rule. {@code 409} is for a request that
 * was valid and lost to the resource's current state or to a concurrent writer. {@code 400} is for
 * anything malformed. See {@code docs/decisions/07-web.md} for why each is the code it is.
 *
 * <p>A {@code 500} says nothing about its cause: not the message, not the type, not a frame. It
 * carries the trace id, and the log line with the same id carries the rest.
 */
@RestControllerAdvice
public class LedgerProblemHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(LedgerProblemHandler.class);
  private static final URI ABOUT_BLANK = URI.create("about:blank");

  @ExceptionHandler(UnknownReferenceException.class)
  ProblemDetail unknownReference(UnknownReferenceException e) {
    return "position".equals(e.kind())
        ? Problems.of(HttpStatus.NOT_FOUND, "not-found", e.getMessage())
        : Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-reference", e.getMessage());
  }

  /** The key names an earlier, different request: answering with its result would mislead. */
  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ProblemDetail keyReused(IdempotencyKeyReusedException e) {
    return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", e.getMessage());
  }

  @ExceptionHandler(InvalidQueryException.class)
  ProblemDetail invalidQuery(InvalidQueryException e) {
    return Problems.of(HttpStatus.BAD_REQUEST, "invalid-query", e.getMessage());
  }

  @ExceptionHandler(MalformedIdentifierException.class)
  ProblemDetail malformedIdentifier(MalformedIdentifierException e) {
    return Problems.of(HttpStatus.BAD_REQUEST, "malformed-identifier", e.getMessage());
  }

  @ExceptionHandler(InsufficientCreditException.class)
  ProblemDetail insufficientCredit(InsufficientCreditException e) {
    return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-credit", e.getMessage());
  }

  @ExceptionHandler(ExcessiveReturnException.class)
  ProblemDetail excessiveReturn(ExcessiveReturnException e) {
    return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "excessive-return", e.getMessage());
  }

  @ExceptionHandler(CurrencyMismatchException.class)
  ProblemDetail currencyMismatch(CurrencyMismatchException e) {
    return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "currency-mismatch", e.getMessage());
  }

  @ExceptionHandler(IllegalTransitionException.class)
  ProblemDetail illegalTransition(IllegalTransitionException e) {
    return Problems.of(HttpStatus.CONFLICT, "illegal-transition", e.getMessage());
  }

  /** Every other rule {@code domain} enforces: a well-formed request the ledger will not book. */
  @ExceptionHandler(DomainException.class)
  ProblemDetail domainRule(DomainException e) {
    return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "domain-rule", e.getMessage());
  }

  /**
   * The optimistic conflict the return's retry loop gave up on, or a pessimistic lock timeout.
   * Retrying later may succeed; the detail says so without naming the mechanism.
   */
  @ExceptionHandler(ConcurrencyFailureException.class)
  ProblemDetail concurrentUpdate(ConcurrencyFailureException e) {
    return Problems.of(
        HttpStatus.CONFLICT,
        "concurrent-update",
        "The position was changed by another request; retry with the same Idempotency-Key");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e, HttpServletRequest request) {
    String traceId = TraceIdFilter.of(request);
    log.error("Unexpected failure serving {} {}", request.getMethod(), request.getRequestURI(), e);
    ProblemDetail problem =
        Problems.of(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal",
            "An unexpected error occurred. Quote the trace id when reporting it.");
    problem.setProperty("traceId", traceId);
    return problem;
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = Problems.of(status, "validation", "The request has invalid fields");
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error(error.getField(), error.getDefaultMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  /**
   * Method validation: a constrained header or parameter, and, once any parameter is constrained,
   * the {@code @Valid} body too. A body's errors are reported by field path ({@code rule.rate}), a
   * parameter's by its name.
   */
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = Problems.of(status, "validation", "The request has invalid fields");
    List<Map<String, String>> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result instanceof ParameterErrors body
                        ? body.getFieldErrors().stream()
                            .map(error -> error(error.getField(), error.getDefaultMessage()))
                        : result.getResolvableErrors().stream()
                            .map(
                                error ->
                                    error(
                                        parameterName(result.getMethodParameter()),
                                        error.getDefaultMessage())))
            .toList();
    problem.setProperty("errors", errors);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  /** The name a client knows a parameter by: the header's or request parameter's, if it has one. */
  private static String parameterName(MethodParameter parameter) {
    RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
    if (header != null && !header.value().isEmpty()) {
      return header.value();
    }
    RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
    if (param != null && !param.value().isEmpty()) {
      return param.value();
    }
    return String.valueOf(parameter.getParameterName());
  }

  private static Map<String, String> error(String field, String message) {
    return Map.of("field", field, "message", String.valueOf(message));
  }

  /**
   * An unreadable body, including every refusal of {@code LedgerJacksonModule}. The detail names
   * the JSON path and Jackson's reason, which describe the client's input, not this service.
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem;
    if (ex.getCause() instanceof DatabindException databind) {
      problem = Problems.of(status, "malformed-body", databind.getOriginalMessage());
      problem.setProperty("path", databind.getPathReference());
    } else {
      problem = Problems.of(status, "malformed-body", "The request body is not readable JSON");
    }
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleServletRequestBindingException(
      ServletRequestBindingException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        ex instanceof MissingRequestHeaderException missing
            ? Problems.of(
                status,
                "missing-header",
                "Required header %s is missing".formatted(missing.getHeaderName()))
            : Problems.of(status, "bad-request", ex.getBody().getDetail());
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  /**
   * Where every framework error passes: those still typed {@code about:blank} get a kind. The body
   * of a framework error is built inside {@code super}, so it is typed after the call, not before.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, status, request);
    if (response != null
        && response.getBody() instanceof ProblemDetail problem
        && (problem.getType() == null || ABOUT_BLANK.equals(problem.getType()))) {
      problem.setType(Problems.type(Problems.kindOf(status)));
    }
    return response;
  }
}
