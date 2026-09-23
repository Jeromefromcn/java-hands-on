package com.jerome.slledger.bootstrap.demo.lifecycle;

import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;

class RecordingBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

  private final CallOrderRecorder recorder;

  RecordingBeanPostProcessor(CallOrderRecorder recorder) {
    this.recorder = recorder;
  }

  @Override
  public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
    if (beanClass == LifecycleLoggingBean.class) {
      recorder.record("bpp.beforeInstantiation");
    }
    return null;
  }

  @Override
  public boolean postProcessAfterInstantiation(Object bean, String beanName) {
    if (bean instanceof LifecycleLoggingBean) {
      recorder.record("bpp.afterInstantiation");
    }
    return true;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof LifecycleLoggingBean) {
      recorder.record("bpp.beforeInitialization");
    }
    return bean;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof LifecycleLoggingBean) {
      recorder.record("bpp.afterInitialization");
    }
    return bean;
  }
}
