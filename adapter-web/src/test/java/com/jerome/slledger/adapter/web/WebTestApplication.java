package com.jerome.slledger.adapter.web;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The configuration {@code @WebMvcTest} slices in this module start from. The slice filter keeps
 * only web components of the scan: controllers, advice, filters, Jackson modules.
 */
@SpringBootApplication
public class WebTestApplication {}
