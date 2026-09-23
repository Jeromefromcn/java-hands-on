package com.jerome.slledger.bootstrap.timing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Every interface method of the annotated bean is timed by {@link TimingBeanPostProcessor}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Timed {}
