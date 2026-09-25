package com.jerome.slledger.adapter.persistence;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Everything this adapter contributes to a context, in one import.
 *
 * <p>The application's component scan covers {@code com.jerome.slledger.bootstrap} only, so the
 * entities, Spring Data repositories and adapters here would never be found without naming this
 * package. It is named once, here, instead of widening the application's scan to the whole code
 * base.
 *
 * <p>The adapters are found by component scan rather than declared as {@code @Bean} methods here:
 * they depend on Spring Data interfaces that are package-private to their own package, which a
 * factory method in this package could not name.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = PersistenceConfiguration.class)
@EntityScan(basePackageClasses = PersistenceConfiguration.class)
@EnableJpaRepositories(basePackageClasses = PersistenceConfiguration.class)
public class PersistenceConfiguration {}
