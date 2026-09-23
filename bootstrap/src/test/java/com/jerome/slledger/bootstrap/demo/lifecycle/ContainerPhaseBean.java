package com.jerome.slledger.bootstrap.demo.lifecycle;

import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextRefreshedEvent;

class ContainerPhaseBean implements SmartLifecycle, ApplicationListener<ContextRefreshedEvent> {

  private final CallOrderRecorder recorder;
  private volatile boolean running;

  ContainerPhaseBean(CallOrderRecorder recorder) {
    this.recorder = recorder;
  }

  @Override
  public void start() {
    running = true;
    recorder.record("SmartLifecycle.start");
  }

  @Override
  public void stop() {
    running = false;
    recorder.record("SmartLifecycle.stop");
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    recorder.record("ContextRefreshedEvent");
  }
}
