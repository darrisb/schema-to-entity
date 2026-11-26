package com.example.dynamicmeta.drift;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.example.dynamicmeta.connection.SecretRedactor;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceNotFoundException;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.data.SourceSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks a configured source for drift: rediscovers its schema, snapshots it
 * and compares the snapshot with the accepted baseline kept in
 * {@link SnapshotStore}.
 * <p>
 * The first check of a source has nothing to compare against, so it stores the
 * snapshot it just took as the baseline and reports
 * {@link DriftReport.Status#BASELINE_CREATED}. Later checks report
 * {@link DriftReport.Status#UNCHANGED} when the structural fingerprint matches
 * the baseline and {@link DriftReport.Status#CHANGED} with the
 * {@link SnapshotDiff} changes when it does not. A source whose schema cannot be
 * discovered at all is reported as {@link DriftReport.Status#UNAVAILABLE} with
 * the generic {@link #DISCOVERY_FAILED} reason: the driver's message can quote a
 * JDBC URL or a password, so it only ever reaches the log, redacted, and an
 * unavailable source never overwrites the baseline or the last checked snapshot.
 * <p>
 * Discovery is forced through {@link SchemaCache#refresh(String)}, so a check
 * always describes the database as it is now, and the refreshed cache is what
 * the data API and the graph then serve. Nothing here writes to a source
 * database: reading metadata is the only thing the check does to it, and the
 * only files written are the baseline snapshots.
 * <p>
 * A report is remembered per source by {@link #lastReport(String)}, along with
 * the snapshot the check produced. {@link #accept(String, String)} promotes that
 * remembered snapshot to the baseline, which is how a reviewer confirms drift
 * they have just been shown: accepting is refused when the fingerprint offered
 * by the reviewer is not the one that was last checked, because the schema moved
 * since they looked.
 * <p>
 * Checks of one source are serialized, so a scheduled run and a reviewer cannot
 * record two different baselines at once. Unknown source names are rejected with
 * {@link SourceNotFoundException} before anything is read or written, so random
 * names cannot grow the remembered reports.
 */
public class DriftService {

    /** Generic reason reported for an unavailable source; never a driver message. */
    public static final String DISCOVERY_FAILED = "Schema discovery failed";

    private static final Logger log = LoggerFactory.getLogger(DriftService.class);

    private final SourceRegistry registry;
    private final SchemaCache cache;
    private final SnapshotBuilder builder;
    private final SnapshotStore store;
    private final Clock clock;
    private final Map<String, DriftReport> lastReports = new ConcurrentHashMap<>();
    private final Map<String, SchemaSnapshot> lastSnapshots = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public DriftService(SourceRegistry registry, SchemaCache cache, SnapshotBuilder builder,
                        SnapshotStore store) {
        this(registry, cache, builder, store, Clock.systemUTC());
    }

    /**
     * @param registry supplies the configured source names checked by {@link #checkAll()}
     * @param cache    rediscovery is forced through this cache
     * @param builder  turns a discovered schema into a fingerprinted snapshot
     * @param store    holds the accepted baseline of each source
     * @param clock    supplies the moments recorded in a snapshot and a report
     */
    public DriftService(SourceRegistry registry, SchemaCache cache, SnapshotBuilder builder,
                        SnapshotStore store, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Rediscovers one source and compares it with its baseline.
     *
     * @param source configured source name
     * @return what the check found; a first check records the baseline and
     *         reports {@link DriftReport.Status#BASELINE_CREATED}
     * @throws SourceNotFoundException when the source is not configured
     * @throws IllegalStateException   when an existing baseline file cannot be
     *                                 read; it is left untouched
     */
    public DriftReport check(String source) {
        requireConfigured(source);
        synchronized (lockFor(source)) {
            SchemaSnapshot baseline = store.baseline(source).orElse(null);
            SchemaSnapshot current;
            try {
                SourceSchema schema = cache.refresh(source);
                current = builder.build(source, schema, clock.instant());
            } catch (RuntimeException e) {
                // Only the redacted failure is logged; the report carries a
                // generic reason, and the baseline stays as it was.
                logFailure(source, e);
                return remember(DriftReport.unavailable(source, baseline,
                        clock.instant(), DISCOVERY_FAILED));
            }
            lastSnapshots.put(source, current);
            if (baseline == null) {
                store.saveBaseline(current);
                return remember(DriftReport.baselineCreated(source, current, current.takenAt()));
            }
            List<SchemaChange> changes = SnapshotDiff.diff(baseline, current);
            return remember(DriftReport.compared(source, baseline, current, changes,
                    current.takenAt()));
        }
    }

    /**
     * Checks every configured source, in name order. One source that cannot be
     * checked because its baseline file is broken stops the run: the report of
     * each source checked before it is still remembered, and fixing the file is
     * a human decision ({@link SnapshotStore}).
     *
     * @return one report per configured source, sorted by source name
     */
    public List<DriftReport> checkAll() {
        List<DriftReport> reports = new ArrayList<>();
        for (String source : new TreeSet<>(registry.names())) {
            reports.add(check(source));
        }
        return List.copyOf(reports);
    }

    /**
     * Accepts the snapshot the last check of this source produced as the new
     * baseline, which is how confirmed drift stops being reported.
     *
     * @param source              configured source name
     * @param expectedFingerprint the fingerprint the reviewer was shown; it must
     *                            be the one the last check took
     * @throws SourceNotFoundException when the source is not configured
     * @throws IllegalStateException   when the source was never checked, or when
     *                                 its fingerprint is not the one expected,
     *                                 meaning the schema moved since the reviewer
     *                                 looked and the newer check should be read
     *                                 and accepted instead
     */
    public void accept(String source, String expectedFingerprint) {
        requireConfigured(source);
        synchronized (lockFor(source)) {
            SchemaSnapshot checked = lastSnapshots.get(source);
            if (checked == null) {
                throw new IllegalStateException("Source '" + source
                        + "' has not been checked for drift yet");
            }
            if (!checked.fingerprint().equals(expectedFingerprint)) {
                throw new IllegalStateException("Source '" + source
                        + "' changed since it was checked: expected fingerprint "
                        + expectedFingerprint + ", last checked snapshot is "
                        + checked.fingerprint());
            }
            store.saveBaseline(checked);
        }
    }

    /**
     * The most recent report of this source, or empty when it has never been
     * checked. Reports are held in memory only, so an application restart
     * clears them while the baselines on disk remain.
     */
    public Optional<DriftReport> lastReport(String source) {
        return source == null ? Optional.empty() : Optional.ofNullable(lastReports.get(source));
    }

    /** Stores the report as the source's most recent one and returns it. */
    private DriftReport remember(DriftReport report) {
        lastReports.put(report.source(), report);
        return report;
    }

    /**
     * Rejects a name that is not configured before anything is read, written or
     * remembered for it, so a random name cannot grow the remembered reports.
     */
    private void requireConfigured(String source) {
        if (source == null || !registry.names().contains(source)) {
            throw new SourceNotFoundException(source);
        }
    }

    /** The object that serializes checks and accepts of one source. */
    private Object lockFor(String source) {
        return locks.computeIfAbsent(source, key -> new Object());
    }

    /** Logs the real failure for operators; the message is still redacted. */
    private static void logFailure(String source, RuntimeException e) {
        log.warn("Drift check of source '{}' failed: {}", source,
                SecretRedactor.redact(e.getMessage()));
        log.debug("Drift check of source '{}' failed", source, e);
    }
}
