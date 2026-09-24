package com.jerome.slledger.bootstrap.demo.conditional;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Exists only when {@link OnOperatingSystemCondition} matches; its presence is the behaviour. */
@Component("diagnosticsBean")
@Conditional(OnOperatingSystemCondition.class)
public class DiagnosticsBean {}
