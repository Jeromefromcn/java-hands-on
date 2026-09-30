package com.jerome.slledger.bootstrap.batch;

import com.jerome.slledger.application.reconciliation.SettlementGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only beans for the settlement ladder: a clock a test moves and a gateway it scripts. */
class LadderSupport {

  static final class MovingClock extends Clock {

    private volatile Instant now;

    MovingClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static final class ScriptedGateway implements SettlementGateway {

    final Map<String, Deque<Result>> script = new ConcurrentHashMap<>();
    final List<String> asked = Collections.synchronizedList(new ArrayList<>());

    void answers(String position, Result... answers) {
      script.put(position, new ArrayDeque<>(List.of(answers)));
    }

    @Override
    public Result resubmit(String positionId) {
      asked.add(positionId);
      Result next = script.getOrDefault(positionId, new ArrayDeque<>()).poll();
      return next != null ? next : new Rejected("no route");
    }
  }

  @Bean
  @Primary
  MovingClock movingClock() {
    return new MovingClock(Instant.parse("2026-09-30T02:00:00Z"));
  }

  @Bean
  @Primary
  ScriptedGateway scriptedGateway() {
    return new ScriptedGateway();
  }
}
