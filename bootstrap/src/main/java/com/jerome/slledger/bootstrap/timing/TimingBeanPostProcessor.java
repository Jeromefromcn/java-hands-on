package com.jerome.slledger.bootstrap.timing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Replaces each {@link Timed} bean with a JDK proxy that logs how long every
 * call took. Hand-rolled
 * on purpose, ahead of Spring AOP in stage 5.
 */
@Component
public class TimingBeanPostProcessor implements BeanPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(TimingBeanPostProcessor.class);

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (AnnotationUtils.findAnnotation(bean.getClass(), Timed.class) == null) {
      return bean;
    }
    Class<?>[] interfaces = ClassUtils.getAllInterfaces(bean);
    if (interfaces.length == 0) {
      // A JDK proxy can only stand in for interfaces; silently skipping would mean
      // @Timed does
      // nothing and nobody notices.
      throw new IllegalStateException(
          "@Timed bean '" + beanName + "' implements no interface, so it cannot be JDK-proxied");
    }
    return Proxy.newProxyInstance(
        bean.getClass().getClassLoader(),
        interfaces,
        (proxy, method, args) -> {
          long start = System.nanoTime();
          try {
            return method.invoke(bean, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          } finally {
            log.info("{}.{} took {} ns", beanName, method.getName(), System.nanoTime() - start);
          }
        });
  }
}
