package com.jerome.slledger.bootstrap.demo.lifecycle;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

class RecordingBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // No bean exists yet, so nothing can be injected here; getBean forces the recorder into
    // existence before any BeanPostProcessor is registered — the hazard of this phase.
    beanFactory.getBean(CallOrderRecorder.class).record("postProcessBeanFactory");
  }
}
