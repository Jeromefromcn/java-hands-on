package com.jerome.slledger.bootstrap.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * When each job last completed, in epoch seconds, read from Spring Batch's own metadata. Reading it
 * from the database and not from a field the process sets means the answer is the same on every
 * instance and survives a restart: a process that has never run the job still knows whether anyone
 * did.
 *
 * <p>A job that has never completed reads {@code 0}, not NaN: an alert of the form "now minus last
 * success is over the window" is true for 0 and silently false for NaN, and "never" must page.
 */
public final class BatchMeters implements MeterBinder {

  private final JdbcClient jdbc;
  private final List<String> jobs;
  private final ZoneId zone;
  private final CachedValue.Clocked cache;

  public BatchMeters(JdbcClient jdbc, List<String> jobs, ZoneId zone) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.zone = zone;
    this.cache = new CachedValue.Clocked(5_000);
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (String job : jobs) {
      Gauge.builder(
              "ledger.batch.last.success.timestamp.seconds",
              () -> cache.get(job, () -> lastSuccess(job)))
          .description("Epoch seconds at which the job last completed; 0 if it never has")
          .tag("job", job)
          .register(registry);
    }
  }

  private double lastSuccess(String job) {
    // max() over no completed run is one row holding NULL, which is "never", not an error.
    Timestamp last =
        jdbc.sql(
                "SELECT max(e.end_time) FROM batch_job_execution e"
                    + " JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id"
                    + " WHERE i.job_name = ? AND e.status = 'COMPLETED'")
            .param(job)
            .query((rs, n) -> rs.getTimestamp(1))
            .optional()
            .orElse(null);
    return last == null ? 0 : last.toLocalDateTime().atZone(zone).toEpochSecond();
  }
}
