package com.jerome.slledger.adapter.persistence.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SharedRedisIT {

  @BeforeAll
  static void start() {
    SharedRedis.start();
  }

  @AfterEach
  void flush() {
    SharedRedis.flushAll();
  }

  @Test
  @Timeout(30)
  void twoClientsSeeOneServer() {
    try (SharedRedis.Client a = SharedRedis.client();
        SharedRedis.Client b = SharedRedis.client()) {
      a.template().opsForValue().set("shared-redis-it:k", "v");
      assertThat(b.template().opsForValue().get("shared-redis-it:k")).isEqualTo("v");
    }
  }

  @Test
  @Timeout(30)
  void flushAllEmptiesTheServer() {
    try (SharedRedis.Client client = SharedRedis.client()) {
      client.template().opsForValue().set("shared-redis-it:k", "v");
      SharedRedis.flushAll();
      assertThat(client.template().hasKey("shared-redis-it:k")).isFalse();
    }
  }
}
