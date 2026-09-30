package com.jerome.slledger.application.cache;

import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The security lookup behind a {@link ReadThrough}, per instance only.
 *
 * <p>Every quote begins with this lookup, and an ISIN nobody holds is answered by a database read
 * every time it is asked about. Caching the miss is what stops a scan of made-up ISINs from
 * becoming a scan of the database.
 */
public final class CachingSecurityDirectory implements SecurityDirectory {

  private final ReadThrough<Isin, Security> cache;

  public CachingSecurityDirectory(
      SecurityDirectory directory, List<ReadThrough.Layer<Isin, Security>> layers, Random random) {
    this.cache = new ReadThrough<>(layers, directory::find, random);
  }

  @Override
  public Optional<Security> find(Isin isin) {
    return cache.get(isin);
  }

  public void evict(Isin isin) {
    cache.evict(isin);
  }

  public CacheCounters counters() {
    return cache.counters();
  }
}
