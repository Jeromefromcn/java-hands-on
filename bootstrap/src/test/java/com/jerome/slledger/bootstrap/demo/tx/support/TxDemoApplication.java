package com.jerome.slledger.bootstrap.demo.tx.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Auto-configuration on, component scan off: each test names exactly the beans it loads. Stage-5
 * demos start from here, never from {@code SlLedgerApplication}, which has no datasource.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TxDemoApplication {}
