package com.example.dynamicmeta.data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.connection.DatabaseType;

/**
 * Executes the read-only queries behind the data API.
 * <p>
 * Every query is built by {@link SelectBuilder} from the cached schema and a
 * validated {@link QuerySpec}; values are always bind parameters. Connections
 * are taken from the {@link SourceRegistry}, and every statement is given the
 * configured query timeout and row limit. Rows are returned as ordered maps
 * with numbers kept as numbers, dates and timestamps rendered as ISO-8601
 * strings and binary columns returned as {@code null}. Whole numbers outside
 * JavaScript's safe integer range are returned as strings so browsers do not
 * round them, and timestamps without a time zone stay local date-times.
 * <p>
 * The service only ever issues {@code SELECT} and {@code SELECT COUNT(*)}
 * statements; it never generates INSERT, UPDATE, DELETE or DDL.
 */
public final class DataQueryService {

    private final SourceRegistry registry;
    private final SchemaCache schemaCache;
    private final QueryParameterParser queryParameterParser;
    private final Duration queryTimeout;

    public DataQueryService(SourceRegistry registry,
                            SchemaCache schemaCache,
                            QueryParameterParser queryParameterParser,
                            SchemaToEntityProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.schemaCache = Objects.requireNonNull(schemaCache, "schemaCache");
        this.queryParameterParser = Objects.requireNonNull(queryParameterParser,
                "queryParameterParser");
        Objects.requireNonNull(properties, "properties");
        Duration configured = properties.getQueryTimeout();
        this.queryTimeout = configured == null ? Duration.ZERO : configured;
    }

    /**
     * Lists the entities discovered for a source.
     *
     * @throws SourceNotFoundException when the source is not configured
     */
    public List<EntityDescriptor> entities(String source) {
        SourceSchema schema = schemaCache.get(source);
        List<EntityDescriptor> entities = new ArrayList<>(schema.entities().size());
        for (EntityModel entity : schema.entities().values()) {
            List<ColumnDescriptor> columns = new ArrayList<>(entity.columns().size());
            for (ColumnModel column : entity.columns()) {
                columns.add(new ColumnDescriptor(
                        column.name(),
                        column.type(),
                        column.category().name(),
                        column.nullable()));
            }
            entities.add(new EntityDescriptor(
                    entity.name(),
                    entity.kind(),
                    entity.keyColumns(),
                    columns));
        }
        return List.copyOf(entities);
    }

    /**
     * Runs a validated page query for one entity.
     *
     * @param includeTotal when false the COUNT statement is skipped and the
     *                     returned total is null
     * @throws SourceNotFoundException when the source is not configured
     * @throws EntityNotFoundException when the entity was not discovered
     * @throws SQLException            when the database read fails
     */
    public DataPage query(String source, String entity,
                          Map<String, List<String>> parameters,
                          boolean includeTotal) throws SQLException {
        SourceSchema schema = schemaCache.get(source);
        EntityModel model = schema.entity(entity);
        QuerySpec query = queryParameterParser.parse(model, parameters);
        SelectBuilder builder = builder(schema);
        SqlStatement select = builder.select(model, query);
        SqlStatement count = includeTotal ? builder.buildCount(model, query) : null;

        try (Connection connection = registry.get(source).getConnection()) {
            Long total = count == null ? null : executeCount(connection, count, query.limit());
            List<Map<String, Object>> items =
                    executeRows(connection, select, query.limit());
            return new DataPage(items, total, query.limit(), query.offset());
        }
    }

    /**
     * Returns one row selected by the entity's single key column.
     *
     * @throws SourceNotFoundException when the source is not configured
     * @throws EntityNotFoundException when the entity was not discovered
     * @throws IllegalArgumentException when the entity has no single-column key
     *                                  or the key value cannot be converted
     * @throws RowNotFoundException    when no row matches the key
     * @throws SQLException            when the database read fails
     */
    public Map<String, Object> byKey(String source, String entity, String key)
            throws SQLException {
        SourceSchema schema = schemaCache.get(source);
        EntityModel model = schema.entity(entity);
        if (model.keyColumns().size() != 1) {
            throw new IllegalArgumentException("This entity has no single-column key");
        }

        ColumnModel keyColumn = resolveColumn(model, model.keyColumns().getFirst());
        Map<String, Object> equals = new LinkedHashMap<>();
        equals.put(keyColumn.name(), convertKey(keyColumn, key));
        QuerySpec query = new QuerySpec(List.of(), equals, Map.of(), List.of(), 1, null);
        SqlStatement select = builder(schema).select(model, query);

        try (Connection connection = registry.get(source).getConnection();
             PreparedStatement statement = prepare(connection, select, 1);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new RowNotFoundException(entity);
            }
            return readRow(resultSet);
        }
    }

    private SelectBuilder builder(SourceSchema schema) {
        DatabaseType databaseType = DatabaseType.fromId(schema.databaseType());
        if (databaseType == null) {
            throw new IllegalArgumentException(
                    "Unsupported database type: " + schema.databaseType());
        }
        return new SelectBuilder(databaseType, schema.catalog(), schema.schema());
    }

    private List<Map<String, Object>> executeRows(Connection connection,
                                                  SqlStatement statement,
                                                  int maxRows) throws SQLException {
        try (PreparedStatement prepared = prepare(connection, statement, maxRows);
             ResultSet resultSet = prepared.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(readRow(resultSet));
            }
            return List.copyOf(rows);
        }
    }

    private Long executeCount(Connection connection, SqlStatement statement,
                             int maxRows) throws SQLException {
        try (PreparedStatement prepared = prepare(connection, statement, maxRows);
             ResultSet resultSet = prepared.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private PreparedStatement prepare(Connection connection, SqlStatement statement,
                                      int maxRows) throws SQLException {
        PreparedStatement prepared = connection.prepareStatement(statement.sql());
        prepared.setQueryTimeout(queryTimeoutSeconds());
        prepared.setMaxRows(maxRows);
        List<Object> parameters = statement.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            prepared.setObject(i + 1, parameters.get(i));
        }
        return prepared;
    }

    /** Whole seconds for {@link PreparedStatement#setQueryTimeout}; 0 means no timeout. */
    private int queryTimeoutSeconds() {
        if (queryTimeout.isZero() || queryTimeout.isNegative()) {
            return 0;
        }
        return (int) Math.max(1L, Math.ceilDiv(queryTimeout.toMillis(), 1000L));
    }

    private static Map<String, Object> readRow(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>(columnCount * 2);
        for (int i = 1; i <= columnCount; i++) {
            row.put(metadata.getColumnLabel(i), readValue(resultSet, metadata, i));
        }
        return row;
    }

    private static Object readValue(ResultSet resultSet, ResultSetMetaData metadata,
                                    int index) throws SQLException {
        int sqlType = metadata.getColumnType(index);
        if (isBinary(sqlType)) {
            return null;
        }
        String temporal = readTemporal(resultSet, metadata, index, sqlType);
        if (temporal != null) {
            return temporal.isEmpty() ? null : temporal;
        }
        Object value = resultSet.getObject(index);
        if (value instanceof Number number) {
            return toJsonNumber(number);
        }
        if (value == null || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof LocalTime time) {
            return time.toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof OffsetTime time) {
            return time.toString();
        }
        if (value instanceof ZonedDateTime dateTime) {
            return dateTime.toString();
        }
        if (value instanceof byte[]) {
            return null;
        }
        return String.valueOf(value);
    }

    /**
     * Reads timestamp columns through the java.time API so a value stored without
     * a time zone is not shifted by the JVM's default zone (java.sql.Timestamp
     * would interpret it in that zone and then print it as UTC).
     *
     * @return the ISO-8601 text, an empty string for SQL NULL, or null when the
     *         column is not a timestamp or the driver cannot convert it (the caller
     *         then falls back to getObject)
     */
    private static String readTemporal(ResultSet resultSet, ResultSetMetaData metadata,
                                       int index, int sqlType) {
        if (sqlType != Types.TIMESTAMP && sqlType != Types.TIMESTAMP_WITH_TIMEZONE) {
            return null;
        }
        String typeName;
        try {
            typeName = String.valueOf(metadata.getColumnTypeName(index)).toLowerCase(java.util.Locale.ROOT);
        } catch (SQLException e) {
            typeName = "";
        }
        boolean withZone = sqlType == Types.TIMESTAMP_WITH_TIMEZONE
                || typeName.contains("timestamptz") || typeName.contains("time zone");
        try {
            if (withZone) {
                OffsetDateTime value = resultSet.getObject(index, OffsetDateTime.class);
                return value == null ? "" : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }
            LocalDateTime value = resultSet.getObject(index, LocalDateTime.class);
            return value == null ? "" : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (SQLException | RuntimeException unsupported) {
            return null;
        }
    }

    /** Largest integer a JavaScript number holds exactly (2^53 - 1). */
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    /**
     * Keeps numbers as numbers unless they are whole numbers beyond JavaScript's
     * safe integer range, which are returned as strings (for example large
     * BIGINT keys) so a browser cannot round them to a different value.
     */
    static Object toJsonNumber(Number number) {
        if (number instanceof Long value) {
            return Math.abs(value) > MAX_SAFE_INTEGER || value == Long.MIN_VALUE
                    ? Long.toString(value) : value;
        }
        if (number instanceof BigInteger value) {
            return value.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) > 0
                    ? value.toString() : value;
        }
        if (number instanceof BigDecimal value) {
            boolean whole = value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
            if (whole && value.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) > 0) {
                return value.toPlainString();
            }
            return value;
        }
        return number;
    }

    private static boolean isBinary(int sqlType) {
        return sqlType == Types.BINARY
                || sqlType == Types.VARBINARY
                || sqlType == Types.LONGVARBINARY
                || sqlType == Types.BLOB;
    }

    /** Resolves a key column to its discovered model, ignoring case. */
    private static ColumnModel resolveColumn(EntityModel entity, String requested) {
        for (ColumnModel column : entity.columns()) {
            if (column.name().equalsIgnoreCase(requested)) {
                return column;
            }
        }
        throw new IllegalArgumentException(
                "Unknown column '" + requested + "' for entity '" + entity.name() + "'");
    }

    /**
     * Converts a URL path key to a bind value for the key column. Unlike the
     * query parser, {@link ColumnCategory#OTHER} columns keep the raw string
     * so single-column keys such as UUIDs can still be queried.
     */
    private static Object convertKey(ColumnModel column, String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("A key value is required");
        }
        try {
            return switch (column.category()) {
                case NUMBER -> new BigDecimal(rawValue.trim());
                case BOOLEAN -> parseBoolean(column, rawValue.trim());
                case DATE -> LocalDate.parse(rawValue.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                case TIMESTAMP -> parseTimestamp(rawValue.trim());
                case TEXT, OTHER -> rawValue;
            };
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Invalid value '" + rawValue + "' for column '" + column.name() + "'");
        }
    }

    private static Boolean parseBoolean(ColumnModel column, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(
                "Invalid value '" + value + "' for column '" + column.name() + "'");
    }

    /** A timestamp with a UTC offset is an instant; otherwise a local date-time. */
    private static Object parseTimestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    /** One column of an entity descriptor. */
    public record ColumnDescriptor(String name, String type, String category,
                                   boolean nullable) {
    }

    /** One entity of a source's entity list. */
    public record EntityDescriptor(String name, String kind, List<String> keyColumns,
                                   List<ColumnDescriptor> columns) {

        public EntityDescriptor {
            keyColumns = List.copyOf(keyColumns);
            columns = List.copyOf(columns);
        }
    }

    /** One page of rows plus the paging metadata. */
    public record DataPage(List<Map<String, Object>> items, Long total, int limit,
                           Integer offset) {

        public DataPage {
            items = List.copyOf(items);
        }
    }
}
