package com.jerome.slledger.bootstrap.demo.lifecycle;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bean, not a static list, so parallel test runs can't see each other's calls. */
class CallOrderRecorder {

  private static final Logger log = LoggerFactory.getLogger(CallOrderRecorder.class);

  private final List<String> calls = new ArrayList<>();

  void record(String call) {
    calls.add(call);
  }

  List<String> calls() {
    StringBuilder lines = new StringBuilder("lifecycle call order:");
    for (int i = 0; i < calls.size(); i++) {
      lines.append(String.format("%n  %d. %s", i + 1, calls.get(i)));
    }
    log.info(lines.toString());
    return List.copyOf(calls);
  }
}
