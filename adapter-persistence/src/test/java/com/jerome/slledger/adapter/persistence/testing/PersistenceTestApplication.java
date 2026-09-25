package com.jerome.slledger.adapter.persistence.testing;

import com.jerome.slledger.adapter.persistence.PersistenceConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration on, component scan off: each test names exactly the beans it loads, and the
 * persistence adapter arrives through the same {@link PersistenceConfiguration} the application
 * imports.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(PersistenceConfiguration.class)
public class PersistenceTestApplication {}
