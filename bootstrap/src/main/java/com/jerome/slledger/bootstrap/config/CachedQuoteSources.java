package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.cache.CachingMarketPriceSource;
import com.jerome.slledger.application.cache.CachingSecurityDirectory;

/**
 * The two decorators the quote path uses. They are held in one record, and are not beans of the
 * {@code MarketPriceSource} or {@code SecurityDirectory} types, on purpose: opening and returning a
 * position read the security inside their transaction and use its market price for the credit
 * check. A price up to a minute old is acceptable to show a client and not to lend against, so only
 * {@code QuotePrice} is given the cached lookups.
 */
public record CachedQuoteSources(
    CachingSecurityDirectory securities, CachingMarketPriceSource prices) {}
