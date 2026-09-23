package com.jerome.slledger.bootstrap.demo.lifecycle;

import org.springframework.beans.factory.SmartInitializingSingleton;

class AllBeansReadyMarker implements SmartInitializingSingleton {

  private final CallOrderRecorder recorder;

  AllBeansReadyMarker(CallOrderRecorder recorder) {
    this.recorder = recorder;
  }

  @Override
  public void afterSingletonsInstantiated() {
    recorder.record("afterSingletonsInstantiated");
  }
}
