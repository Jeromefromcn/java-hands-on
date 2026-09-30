package com.jerome.slledger.adapter.web.testing;

import com.jerome.slledger.domain.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A test-only controller whose next response or exception a test chooses. Lets the filter and the
 * exception handler be exercised without any production use case behind them.
 */
@RestController
public class ProbeController {

  private static volatile Supplier<Object> next = () -> "ok";

  public static void answer(Supplier<Object> supplier) {
    next = supplier;
  }

  public static void throwing(RuntimeException exception) {
    next =
        () -> {
          throw exception;
        };
  }

  public static void reset() {
    next = () -> "ok";
  }

  @GetMapping("/probe")
  public Object probe() {
    return next.get();
  }

  @PostMapping(path = "/probe", consumes = "application/json")
  public Echo post(@Valid @RequestBody Echo body) {
    next.get();
    return body;
  }

  @GetMapping("/probe/header")
  public String header(@RequestHeader("Idempotency-Key") String key) {
    return key;
  }

  public record Echo(@NotBlank String value, Money money) {}
}
