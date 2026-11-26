package com.example.dynamicmeta.drift;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.example.dynamicmeta.data.ColumnModel;
import com.example.dynamicmeta.data.EntityModel;
import com.example.dynamicmeta.data.ForeignKeyModel;
import com.example.dynamicmeta.data.SourceSchema;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotColumn;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotEntity;
import com.example.dynamicmeta.drift.SchemaSnapshot.SnapshotForeignKey;

/**
 * Turns a discovered {@link SourceSchema} into a canonical {@link SchemaSnapshot}
 * with a structural fingerprint.
 * <p>
 * Entities, their columns and their foreign keys are sorted by lower-cased
 * name, so the snapshot never depends on the order the driver happened to
 * report objects in. The {@code fingerprint} is the lower-case hex SHA-256 of a
 * canonical string built from that sorted structure: identifier names are
 * lower-cased, and column types, nullability, key columns and declared foreign
 * keys are included. Volatile things are excluded on purpose — {@code takenAt},
 * {@code databaseType}, the source name and any labels — so the fingerprint
 * changes exactly when the structure changes and not when a snapshot is merely
 * retaken.
 * <p>
 * Every field in the canonical string is written length-prefixed and tagged.
 * That makes the encoding self-delimiting: two different structures always
 * produce two different strings, so no field boundary can blur into a
 * neighbouring one and collide to the same hash. Inferred foreign keys
 * ({@link ForeignKeyModel#INFERRED}) can appear or disappear without the
 * schema changing, so they are dropped here and never reach the snapshot or
 * the fingerprint. Hidden columns are already gone from the source schema and
 * are not added back.
 * <p>
 * The builder only reads the discovered model: it never connects to or writes
 * to any source database.
 */
public class SnapshotBuilder {

    /** Prefix that identifies the canonical layout; bump it if the encoding changes. */
    private static final String CANONICAL_PREFIX = "schema-drift-v1;";

    /**
     * Builds a snapshot of one source's schema.
     *
     * @param source   name of the source being snapshotted; recorded, not fingerprinted
     * @param schema   the discovered schema to snapshot
     * @param takenAt  when the snapshot was taken; recorded, never fingerprinted
     * @return a canonical snapshot with a stable structural fingerprint
     */
    public SchemaSnapshot build(String source, SourceSchema schema, Instant takenAt) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(takenAt, "takenAt");

        List<SnapshotEntity> entities = new ArrayList<>();
        for (EntityModel model : schema.entities().values()) {
            entities.add(toSnapshotEntity(model));
        }
        entities.sort(Comparator.comparing(entity -> lower(entity.name())));

        String fingerprint = sha256Hex(canonicalString(entities));
        return new SchemaSnapshot(source, fingerprint, takenAt, schema.databaseType(), entities);
    }

    /**
     * Converts one discovered entity into a sorted snapshot entity, keeping the
     * declared foreign keys only and the key columns in their reported order.
     */
    private static SnapshotEntity toSnapshotEntity(EntityModel model) {
        List<SnapshotColumn> columns = new ArrayList<>();
        for (ColumnModel column : model.columns()) {
            columns.add(new SnapshotColumn(column.name(), column.type(), column.category(),
                    column.nullable()));
        }
        columns.sort(Comparator.comparing(column -> lower(column.name())));

        // Key order is meaningful (a composite key), so it is preserved as reported.
        List<String> keyColumns = List.copyOf(model.keyColumns());

        List<SnapshotForeignKey> foreignKeys = new ArrayList<>();
        for (ForeignKeyModel foreignKey : model.foreignKeys()) {
            if (ForeignKeyModel.INFERRED.equals(foreignKey.origin())) {
                // Inferred relationships are volatile: excluded from the snapshot
                // and therefore from the fingerprint.
                continue;
            }
            foreignKeys.add(new SnapshotForeignKey(foreignKey.column(),
                    foreignKey.referencedTable(), foreignKey.referencedColumn()));
        }
        foreignKeys.sort(Comparator
                .comparing((SnapshotForeignKey foreignKey) -> lower(foreignKey.column()))
                .thenComparing(foreignKey -> lower(foreignKey.referencedTable()))
                .thenComparing(foreignKey -> lower(foreignKey.referencedColumn())));

        return new SnapshotEntity(model.name(), model.kind(), columns, keyColumns, foreignKeys);
    }

    /**
     * Builds the canonical string the fingerprint hashes. Entities arrive
     * already sorted; names are lower-cased here, and every field is tagged and
     * length-prefixed so field boundaries are unambiguous and distinct
     * structures cannot collide to the same string. Types are included as
     * reported; nullability, key columns and declared foreign keys are included;
     * categories, {@code takenAt} and labels are not.
     */
    private static String canonicalString(List<SnapshotEntity> entities) {
        StringBuilder canonical = new StringBuilder(CANONICAL_PREFIX);
        appendCount(canonical, "E", entities.size());
        for (SnapshotEntity entity : entities) {
            appendField(canonical, "en", lower(entity.name()));
            appendField(canonical, "ek", lower(entity.kind()));

            appendCount(canonical, "C", entity.columns().size());
            for (SnapshotColumn column : entity.columns()) {
                appendField(canonical, "cn", lower(column.name()));
                appendField(canonical, "ct", column.type());
                appendField(canonical, "cnl", column.nullable() ? "1" : "0");
            }

            appendCount(canonical, "K", entity.keyColumns().size());
            for (String key : entity.keyColumns()) {
                appendField(canonical, "kn", lower(key));
            }

            appendCount(canonical, "F", entity.foreignKeys().size());
            for (SnapshotForeignKey foreignKey : entity.foreignKeys()) {
                appendField(canonical, "fc", lower(foreignKey.column()));
                appendField(canonical, "frt", lower(foreignKey.referencedTable()));
                appendField(canonical, "frc", lower(foreignKey.referencedColumn()));
            }
        }
        return canonical.toString();
    }

    /**
     * Appends one self-delimiting token: {@code tag:length:value;}. The length
     * lets a reader (and the hash) know exactly where the value ends, so a value
     * containing a separator can never be confused with the next field.
     */
    private static void appendField(StringBuilder canonical, String tag, String value) {
        canonical.append(tag).append(value.length()).append(':').append(value).append(';');
    }

    /** Appends a section's element count so a change in arity is always visible. */
    private static void appendCount(StringBuilder canonical, String tag, int count) {
        canonical.append(tag).append('=').append(count).append(';');
    }

    /** Lower-cases with the root locale so an identifier's case never affects the hash. */
    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** Returns the lower-case hex SHA-256 of {@code text}'s UTF-8 bytes. */
    private static String sha256Hex(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java implementation; its absence is a JVM fault.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
