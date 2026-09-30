package com.jerome.slledger.application.messaging;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;

/**
 * In-process: a price update was stored. Published inside the transaction that stored it, so a
 * listener that must act only on committed data registers for the commit. What another system must
 * hear about goes through the outbox instead.
 */
public record PriceApplied(Isin security, Money price, long version) {}
