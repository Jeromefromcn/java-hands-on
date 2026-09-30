package com.jerome.slledger.bootstrap.config;

/** A point-in-time reading of the market data executor's counters. */
public record PoolStats(
    int poolSize, int largestPoolSize, int active, int queued, long completed, long rejected) {}
