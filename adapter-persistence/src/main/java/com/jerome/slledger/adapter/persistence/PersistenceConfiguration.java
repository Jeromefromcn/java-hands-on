package com.jerome.slledger.adapter.persistence;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Everything this adapter contributes to a context, in one import.
 *
 * <p>The application's component scan covers {@code com.jerome.slledger.bootstrap} only, so the
 * entities and Spring Data repositories here would never be found without naming this package. It
 * is named once, here, instead of widening the application's scan to the whole code base.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = PersistenceConfiguration.class)
@EnableJpaRepositories(basePackageClasses = PersistenceConfiguration.class)
public class PersistenceConfiguration {}
