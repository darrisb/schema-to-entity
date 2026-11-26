package com.example.dynamicmeta.drift;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The outcome of one {@link DriftService#check(String)} for one source: what
 * the accepted baseline was, what the source looks like now and how the two
 * differ.
 * <p>
 * The {@link Status} says which of the four cases happened:
 * <ul>
 *   <li>{@link Status#BASELINE_CREATED} — the source had no baseline, so the
 *       snapshot taken by this check became it. There is nothing to compare
 *       with, so {@code changes} is empty and both fingerprints are the same.</li>
 *   <li>{@link Status#UNCHANGED} — the current snapshot fingerprints exactly
 *       like the baseline.</li>
 *   <li>{@link Status#CHANGED} — {@link #changes()} describes every difference,
 *       as classified by {@link SnapshotDiff}, and {@link #impact()} says what
 *       each of them affects.</li>
 *   <li>{@link Status#UNAVAILABLE} — the schema could not be discovered, so no
 *       current snapshot exists: {@code currentFingerprint} is null,
 *       {@code changes} is empty and {@code error} carries the generic reason.
 *       The driver's own message is never used, because it can quote a JDBC URL
 *       or a password.</li>
 * </ul>
 * {@code baselineFingerprint} and {@code baselineTakenAt} describe the baseline
 * this check was compared against and are null when the source has none, as for
 * a first check that failed before recording one.
 * <p>
 * {@link #impact()} says what each change affects: {@link DriftService} runs
 * every {@link #changes()} entry through {@link ImpactAnalyzer}, so the report
 * carries one {@link ChangeImpact} per change, in the same order, naming the
 * overlay labels, relationship decisions and manual relationships and the
 * baseline foreign keys that refer to it. A change nothing refers to still has
 * an entry, with empty lists.
 * <p>
 * This is a value object only: nothing in it reads from or writes to any source
 * database.
 *
 * @param source             name of the source that was checked
 * @param status             what the check found
 * @param baselineFingerprint fingerprint of the accepted baseline, null when there is none
 * @param currentFingerprint fingerprint of the snapshot taken now, null when the source was unavailable
 * @param baselineTakenAt    when the baseline snapshot was taken, null when there is none
 * @param checkedAt          when this check ran
 * @param changes            the differences from the baseline, empty unless the source changed
 * @param impact             what each change affects, one entry per change
 * @param error              generic reason the source was unavailable, null otherwise
 */
public record DriftReport(String source, Status status, String baselineFingerprint,
                          String currentFingerprint, Instant baselineTakenAt, Instant checkedAt,
                          List<SchemaChange> changes, List<ChangeImpact> impact, String error) {

    public DriftReport {
        source = Objects.requireNonNull(source, "source");
        status = Objects.requireNonNull(status, "status");
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        impact = List.copyOf(Objects.requireNonNull(impact, "impact"));
    }

    /**
     * A report for a source that had no baseline: the snapshot taken by this
     * check was stored as the baseline, so the two fingerprints are the same and
     * there is nothing to report as changed.
     */
    public static DriftReport baselineCreated(String source, SchemaSnapshot baseline,
                                              Instant checkedAt) {
        return new DriftReport(source, Status.BASELINE_CREATED, baseline.fingerprint(),
                baseline.fingerprint(), baseline.takenAt(), checkedAt, List.of(), List.of(), null);
    }

    /**
     * A report comparing the current snapshot with the accepted baseline:
     * {@link Status#UNCHANGED} when the diff is empty, {@link Status#CHANGED}
     * when it is not.
     *
     * @param impact what each change affects, one entry per change, as
     *               {@link ImpactAnalyzer} analyzed them; {@link #baselineCreated}
     *               and {@link #unavailable} have no changes to report impact for
     */
    public static DriftReport compared(String source, SchemaSnapshot baseline,
                                       SchemaSnapshot current, List<SchemaChange> changes,
                                       List<ChangeImpact> impact, Instant checkedAt) {
        Status status = changes.isEmpty() ? Status.UNCHANGED : Status.CHANGED;
        return new DriftReport(source, status, baseline.fingerprint(), current.fingerprint(),
                baseline.takenAt(), checkedAt, changes, impact, null);
    }

    /**
     * A report for a source whose schema could not be discovered. Only the
     * generic {@code reason} is kept; it is never the driver's message, and the
     * baseline is reported when one exists so a reviewer can still see what the
     * source is supposed to look like.
     */
    public static DriftReport unavailable(String source, SchemaSnapshot baseline,
                                          Instant checkedAt, String reason) {
        return new DriftReport(source, Status.UNAVAILABLE,
                baseline == null ? null : baseline.fingerprint(), null,
                baseline == null ? null : baseline.takenAt(), checkedAt,
                List.of(), List.of(), Objects.requireNonNull(reason, "reason"));
    }

    /** True when this check found the source different from its baseline. */
    public boolean isChanged() {
        return status == Status.CHANGED;
    }

    /** What a drift check found. */
    public enum Status {

        /** The current snapshot matches the accepted baseline. */
        UNCHANGED("unchanged"),
        /** The current snapshot differs from the accepted baseline. */
        CHANGED("changed"),
        /** The source had no baseline, so this check recorded one. */
        BASELINE_CREATED("baseline-created"),
        /** The source's schema could not be discovered. */
        UNAVAILABLE("unavailable");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        /** The status as written in JSON: {@code baseline-created}, not {@code BASELINE_CREATED}. */
        @JsonValue
        public String wireName() {
            return wireName;
        }
    }
}
