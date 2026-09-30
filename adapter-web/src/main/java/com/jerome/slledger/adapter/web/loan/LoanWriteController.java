package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.adapter.web.validation.IdempotencyKeyHeader;
import com.jerome.slledger.application.position.IdempotentBooking;
import com.jerome.slledger.application.position.Outcome;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The write side of {@code /loans}. Both endpoints require an {@code Idempotency-Key}: a retry with
 * the same key and body answers what the first call answered, with {@code Idempotent-Replayed:
 * true}; the same key with a different body is a {@code 422}.
 */
@RestController
@RequestMapping("/loans")
public class LoanWriteController {

  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  public static final String REPLAYED = "Idempotent-Replayed";

  private final IdempotentBooking booking;

  LoanWriteController(IdempotentBooking booking) {
    this.booking = booking;
  }

  @PostMapping(consumes = "application/json")
  public ResponseEntity<BookedResponse> open(
      @RequestHeader(IDEMPOTENCY_KEY) @IdempotencyKeyHeader String key,
      @Valid @RequestBody OpenLoanRequest request) {
    Outcome outcome = booking.open(request.toCommand(key));
    BookedResponse body = BookedResponse.of(outcome.trade());
    return answer(ResponseEntity.created(URI.create("/loans/" + body.positionId())), outcome, body);
  }

  /** {@code 201} with no {@code Location}: a return has no resource of its own to point at. */
  @PostMapping(path = "/{id}/returns", consumes = "application/json")
  public ResponseEntity<BookedResponse> returnQuantity(
      @RequestHeader(IDEMPOTENCY_KEY) @IdempotencyKeyHeader String key,
      @PathVariable String id,
      @Valid @RequestBody ReturnRequest request) {
    Outcome outcome = booking.returnQuantity(request.toCommand(key, id));
    return answer(
        ResponseEntity.status(HttpStatus.CREATED), outcome, BookedResponse.of(outcome.trade()));
  }

  private static ResponseEntity<BookedResponse> answer(
      ResponseEntity.BodyBuilder builder, Outcome outcome, BookedResponse body) {
    if (outcome.replayed()) {
      builder.header(REPLAYED, "true");
    }
    return builder.body(body);
  }
}
