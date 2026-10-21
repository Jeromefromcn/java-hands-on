package com.jerome.slledger.bootstrap.delivery;

import java.util.concurrent.CountDownLatch;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A request that takes as long as it is told to: the in-flight request a shutdown has to deal with.
 * Test-only, registered by {@link ShutdownIT}; not on the production classpath.
 */
@RestController
public class SlowController {

  /** Counted down when a request is inside the handler; reset by the test before each request. */
  static volatile CountDownLatch entered = new CountDownLatch(1);

  @GetMapping("/test/slow")
  @PreAuthorize("isAuthenticated()")
  public String slow(@RequestParam long millis) throws InterruptedException {
    entered.countDown();
    Thread.sleep(millis);
    return "done after " + millis + " ms";
  }
}
