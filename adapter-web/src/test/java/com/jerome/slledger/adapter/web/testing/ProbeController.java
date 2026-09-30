package com.jerome.slledger.adapter.web.testing;

import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  public static void reset() {
    next = () -> "ok";
  }

  @GetMapping("/probe")
  public Object probe() {
    return next.get();
  }

  @PostMapping(path = "/probe", consumes = "application/json")
  public Object post(@RequestBody Echo body) {
    next.get();
    return body;
  }

  public record Echo(String value) {}
}
