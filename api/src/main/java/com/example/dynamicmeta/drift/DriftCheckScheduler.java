package com.example.dynamicmeta.drift;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link DriftService#checkAll()} on the interval configured by
 * {@code schema-to-entity.drift-check-interval}, so baselines age and drift is
 * noticed without anyone asking for it.
 * <p>
 * The property is unset by default, which means no scheduler is registered and
 * nothing is discovered on a timer: drift is then only checked when a caller
 * asks. When it is set, one daemon thread runs one check at a time — the next
 * run starts one interval after the previous one finished, so a slow source
 * never stacks up overlapping discoveries — and the first run waits a full
 * interval so starting the application never blocks on a database that may be
 * down. {@link #stop()} shuts the thread down; the thread is a daemon, so an
 * application that forgets to stop still exits.
 * <p>
 * A check that throws (a baseline file that cannot be read, say) is logged and
 * skipped rather than killing the schedule, because one bad file should not stop
 * the other sources being watched. Like every other part of drift detection,
 * this writes nothing to a source database: the periodic check reads metadata
 * and may write baseline snapshots.
 */
public final class DriftCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(DriftCheckScheduler.class);

    private final DriftService driftService;
    private final Duration interval;
    private ScheduledExecutorService executor;

    /**
     * @param driftService checked repeatedly while the scheduler runs
     * @param interval     delay between checks; a null or non-positive interval
     *                     leaves the scheduler stopped, as if unset
     */
    public DriftCheckScheduler(DriftService driftService, Duration interval) {
        this.driftService = Objects.requireNonNull(driftService, "driftService");
        this.interval = interval;
    }

    /**
     * Starts the periodic check. Does nothing when a positive interval is not
     * configured or when the scheduler is already running, so calling it twice
     * still leaves exactly one thread.
     */
    public synchronized void start() {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.debug("drift checking is not scheduled: no positive drift-check-interval");
            return;
        }
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(running -> {
            Thread thread = new Thread(running, "schema-drift-check");
            thread.setDaemon(true);
            return thread;
        });
        long delayMillis = interval.toMillis();
        executor.scheduleWithFixedDelay(this::checkAllQuietly, delayMillis, delayMillis,
                TimeUnit.MILLISECONDS);
        log.info("drift checks scheduled every {}", interval);
    }

    /** Stops the periodic check and interrupts a check that is still running. */
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
        log.info("drift checks stopped");
    }

    /** True while the periodic check is scheduled. */
    public synchronized boolean isRunning() {
        return executor != null;
    }

    /**
     * One scheduled run. Failures are logged, never thrown: an exception
     * escaping here would cancel every later run.
     */
    private void checkAllQuietly() {
        try {
            List<DriftReport> reports = driftService.checkAll();
            for (DriftReport report : reports) {
                if (report.isChanged() || report.status() == DriftReport.Status.UNAVAILABLE) {
                    log.warn("drift: source '{}' is {} ({} change(s))", report.source(),
                            report.status().wireName(), report.changes().size());
                }
            }
        } catch (RuntimeException e) {
            log.warn("scheduled drift check failed: {}", e.getClass().getSimpleName());
            log.debug("scheduled drift check failed", e);
        }
    }
}
