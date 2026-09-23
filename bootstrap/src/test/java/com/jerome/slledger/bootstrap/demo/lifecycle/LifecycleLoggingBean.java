package com.jerome.slledger.bootstrap.demo.lifecycle;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

class LifecycleLoggingBean implements BeanNameAware, InitializingBean, DisposableBean {

  private final CallOrderRecorder recorder;

  LifecycleLoggingBean(CallOrderRecorder recorder) {
    this.recorder = recorder;
    recorder.record("constructor");
  }

  // Environment only because the container always has one; what matters is when the setter runs.
  @Autowired
  void setEnvironment(Environment environment) {
    recorder.record("setter injection");
  }

  @Override
  public void setBeanName(String name) {
    recorder.record("setBeanName");
  }

  @PostConstruct
  void init() {
    recorder.record("@PostConstruct");
  }

  @Override
  public void afterPropertiesSet() {
    recorder.record("afterPropertiesSet");
  }

  void initMethod() {
    recorder.record("initMethod");
  }

  @PreDestroy
  void preDestroy() {
    recorder.record("@PreDestroy");
  }

  @Override
  public void destroy() {
    recorder.record("destroy");
  }

  void destroyMethod() {
    recorder.record("destroyMethod");
  }
}
