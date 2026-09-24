package com.jerome.slledger.bootstrap.demo.tx.async;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Turns on {@code @Async}; Boot's auto-configured task executor runs the methods. */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {}
