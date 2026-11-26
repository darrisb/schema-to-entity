package com.example.dynamicmeta.drift;

import java.time.Instant;

import com.example.dynamicmeta.data.SourceSchema;

/**
 * DR-008: the short schema version the data API publishes with every response.
 * <p>
 * The version is the first {@value #LENGTH} lower-case hex characters of the
 * {@link SchemaSnapshot} fingerprint of the source's current discovered schema,
 * so it changes exactly when the structure changes — the same rule that makes
 * fingerprints useful for drift review — and stays stable while
 * {@link com.example.dynamicmeta.data.SchemaCache} serves one cached schema.
 * <p>
 * The snapshot's volatile fields never enter the fingerprint
 * ({@link SnapshotBuilder}), so {@code takenAt} and the source name cannot
 * change the version of an unchanged schema. The builder only reads the
 * discovered model: nothing here connects to or writes a source database.
 */
public final class SchemaVersion {

    /** Leading hex characters of the snapshot fingerprint used as the version. */
    public static final int LENGTH = 12;

    /** The builder is stateless, so one shared instance serves every source. */
    private static final SnapshotBuilder BUILDER = new SnapshotBuilder();

    private SchemaVersion() {
    }

    /**
     * The version of one discovered schema: the first {@value #LENGTH}
     * characters of the fingerprint of the snapshot taken at {@code takenAt}.
     *
     * @param source   name of the source; recorded, never fingerprinted
     * @param schema   the cached {@link SourceSchema} being served
     * @param takenAt  the moment the schema was discovered
     * @return a short, structure-only version string
     */
    public static String of(String source, SourceSchema schema, Instant takenAt) {
        return truncate(BUILDER.build(source, schema, takenAt).fingerprint());
    }

    /**
     * The first {@value #LENGTH} characters of a full snapshot fingerprint, so
     * a version read from a stored snapshot matches a freshly computed one.
     */
    public static String truncate(String fingerprint) {
        return fingerprint.substring(0, Math.min(LENGTH, fingerprint.length()));
    }
}
