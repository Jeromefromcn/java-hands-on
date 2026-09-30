package com.jerome.slledger.adapter.web;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Everything this adapter contributes to a context, in one import: controllers, the exception
 * handler, the JSON module, the trace-id filter and the market data client.
 *
 * <p>Named once here for the same reason as {@code PersistenceConfiguration}: the application's own
 * component scan covers {@code com.jerome.slledger.bootstrap} only.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = WebConfiguration.class)
public class WebConfiguration {}
