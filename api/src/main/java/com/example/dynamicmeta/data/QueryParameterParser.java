package com.example.dynamicmeta.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.example.dynamicmeta.SchemaToEntityProperties;

/**
 * Turns HTTP query parameters into a validated {@link QuerySpec} for the
 * read-only data API.
 * <p>
 * Supported parameters:
 * <ul>
 *   <li>{@code fields=a,b} selects columns in request order; omitted or blank
 *       means every column</li>
 *   <li>{@code sort=a,-b} orders by {@code a} ascending, then {@code b}
 *       descending; a leading {@code -} means descending and {@code +} or no
 *       prefix means ascending</li>
 *   <li>{@code limit=n} is optional and defaults to the configured
 *       {@code default-page-size}; it must be between 1 and the configured
 *       {@code max-page-size}</li>
 *   <li>{@code offset=n} is optional and must be between 0 and
 *       {@value #MAX_OFFSET}</li>
 *   <li>{@code eq.<column>=value} adds an exact-match filter</li>
 *   <li>{@code neq.<column>=value} adds a not-equal filter and converts the
 *       value in the same way as {@code eq.}</li>
 *   <li>{@code isnull.<column>=true|false} adds an {@code IS NULL} or
 *       {@code IS NOT NULL} filter</li>
 *   <li>{@code contains.<column>=text} adds a case-insensitive substring
 *       filter; only {@link ColumnCategory#TEXT} columns are accepted</li>
 *   <li>{@code gt.}, {@code gte.}, {@code lt.}, and {@code lte.} add range
 *       filters for number, date, and timestamp columns</li>
 * </ul>
 * The {@code eq.} and {@code contains.} prefixes keep filters from clashing
 * with columns named like reserved parameters such as {@code limit}.
 * <p>
 * Column names are matched case-insensitively and resolved to the entity's
 * discovered spelling. Values are converted from the column's
 * {@link ColumnCategory}: {@link ColumnCategory#NUMBER} to
 * {@link BigDecimal}, {@link ColumnCategory#BOOLEAN} to {@link Boolean},
 * {@link ColumnCategory#DATE} to {@link LocalDate} ({@code yyyy-MM-dd}),
 * {@link ColumnCategory#TIMESTAMP} to {@link Instant} when the value carries a
 * UTC offset (for example {@code 2026-01-15T10:30:00Z}) or to
 * {@link LocalDateTime} for a local ISO-8601 date-time, and
 * {@link ColumnCategory#TEXT} as-is.
 * <p>
 * Unknown parameters, unknown columns, more than {@value #MAX_FILTERS}
 * filters, more than {@value #MAX_SORT_COLUMNS} sort columns and values that
 * cannot be converted are rejected with {@link IllegalArgumentException}
 * naming the offending column or parameter. The parser only describes a
 * read-only query; it never generates INSERT, UPDATE, DELETE or DDL.
 */
public final class QueryParameterParser {

    /** Maximum number of filter parameters across all filter kinds. */
    public static final int MAX_FILTERS = 10;
    /** Maximum number of sort columns in one request. */
    public static final int MAX_SORT_COLUMNS = 3;
    /** Maximum number of rows a caller may skip. */
    public static final int MAX_OFFSET = 1_000_000;

    private static final String FIELDS = "fields";
    private static final String SORT = "sort";
    private static final String LIMIT = "limit";
    private static final String OFFSET = "offset";
    private static final String EQUALS_PREFIX = "eq.";
    private static final String NOT_EQUALS_PREFIX = "neq.";
    private static final String IS_NULL_PREFIX = "isnull.";
    private static final String CONTAINS_PREFIX = "contains.";
    private static final Map<String, QuerySpec.RangeOperator> RANGE_PREFIXES = Map.of(
            "gte.", QuerySpec.RangeOperator.GTE,
            "lte.", QuerySpec.RangeOperator.LTE,
            "gt.", QuerySpec.RangeOperator.GT,
            "lt.", QuerySpec.RangeOperator.LT);

    private final int defaultPageSize;
    private final int maxPageSize;

    /**
     * Creates a parser with explicit page-size bounds.
     *
     * @param defaultPageSize rows returned when the caller does not send
     *                        {@code limit}; must be at least 1
     * @param maxPageSize     largest allowed {@code limit}; must be at least 1
     *                        and not smaller than {@code defaultPageSize}
     */
    public QueryParameterParser(int defaultPageSize, int maxPageSize) {
        if (defaultPageSize < 1) {
            throw new IllegalArgumentException("defaultPageSize must be at least 1");
        }
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("maxPageSize must be at least 1");
        }
        if (defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException("defaultPageSize must not exceed maxPageSize");
        }
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
    }

    /**
     * Creates a parser from the configured page-size settings.
     *
     * @param properties data API settings supplying the page-size bounds
     */
    public QueryParameterParser(SchemaToEntityProperties properties) {
        this(Objects.requireNonNull(properties, "properties").getDefaultPageSize(),
                properties.getMaxPageSize());
    }

    /**
     * Parses and validates query parameters for one entity.
     *
     * @param entity     discovered table or view whose columns may be used
     * @param parameters raw query parameters; values are the raw strings
     * @return an immutable, validated query description
     * @throws IllegalArgumentException for an unknown parameter or column, an
     *                                  out-of-range page value, too many filters
     *                                  or sort columns, or a value that cannot
     *                                  be converted for its column
     */
    public QuerySpec parse(EntityModel entity, Map<String, List<String>> parameters) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(parameters, "parameters");

        Map<String, ColumnModel> columns = indexColumns(entity);
        List<String> requestedFields = new ArrayList<>();
        List<QuerySpec.Sort> sorts = new ArrayList<>();
        Map<String, Object> equals = new LinkedHashMap<>();
        Map<String, Object> notEquals = new LinkedHashMap<>();
        Map<String, String> contains = new LinkedHashMap<>();
        Map<String, Boolean> nullChecks = new LinkedHashMap<>();
        List<QuerySpec.RangeFilter> ranges = new ArrayList<>();
        Set<String> rangeKeys = new HashSet<>();
        Integer limit = null;
        Integer offset = null;

        for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
            String name = parameter.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Query parameter names must not be blank");
            }
            List<String> values = parameter.getValue();
            if (FIELDS.equals(name)) {
                requestedFields.addAll(splitCsv(values));
            } else if (SORT.equals(name)) {
                sorts.addAll(parseSorts(splitCsv(values), columns, entity));
            } else if (LIMIT.equals(name)) {
                limit = parseLimit(singleValue(name, values));
            } else if (OFFSET.equals(name)) {
                offset = parseOffset(singleValue(name, values));
            } else if (name.startsWith(EQUALS_PREFIX)) {
                addEquals(columns, entity, equals, contains,
                        name.substring(EQUALS_PREFIX.length()), singleValue(name, values));
            } else if (name.startsWith(NOT_EQUALS_PREFIX)) {
                addConvertedFilter(columns, entity, notEquals,
                        name.substring(NOT_EQUALS_PREFIX.length()), singleValue(name, values));
            } else if (name.startsWith(IS_NULL_PREFIX)) {
                addNullCheck(columns, entity, nullChecks,
                        name.substring(IS_NULL_PREFIX.length()), singleValue(name, values));
            } else if (name.startsWith(CONTAINS_PREFIX)) {
                addContains(columns, entity, equals, contains,
                        name.substring(CONTAINS_PREFIX.length()), singleValue(name, values));
            } else if (rangePrefix(name) != null) {
                String prefix = rangePrefix(name);
                addRange(columns, entity, ranges, rangeKeys, equals.size() + contains.size(),
                        RANGE_PREFIXES.get(prefix), name.substring(prefix.length()),
                        singleValue(name, values));
            } else {
                throw new IllegalArgumentException("Unknown query parameter '" + name + "'");
            }
        }

        if (sorts.size() > MAX_SORT_COLUMNS) {
            throw new IllegalArgumentException(
                    "At most " + MAX_SORT_COLUMNS + " sort columns are allowed");
        }
        if (equals.size() + notEquals.size() + contains.size() + nullChecks.size()
                + ranges.size() > MAX_FILTERS) {
            throw new IllegalArgumentException(
                    "At most " + MAX_FILTERS + " filters are allowed");
        }

        List<String> fields = new ArrayList<>(requestedFields.size());
        for (String requested : requestedFields) {
            fields.add(resolveColumn(columns, entity, requested).name());
        }

        int effectiveLimit = limit == null ? defaultPageSize : limit;
        return new QuerySpec(fields, equals, notEquals, contains, nullChecks, ranges, sorts,
                effectiveLimit, offset);
    }

    private void addConvertedFilter(Map<String, ColumnModel> columns, EntityModel entity,
                                    Map<String, Object> filters, String requestedColumn,
                                    String rawValue) {
        ColumnModel column = resolveColumn(columns, entity, requestedColumn);
        if (filters.put(column.name(), convert(column, rawValue)) != null) {
            throw new IllegalArgumentException(
                    "Duplicate filter for column '" + column.name() + "'");
        }
    }

    private void addNullCheck(Map<String, ColumnModel> columns, EntityModel entity,
                              Map<String, Boolean> nullChecks, String requestedColumn,
                              String rawValue) {
        ColumnModel column = resolveColumn(columns, entity, requestedColumn);
        Boolean isNull = parseBoolean(column, rawValue.trim());
        if (nullChecks.put(column.name(), isNull) != null) {
            throw new IllegalArgumentException(
                    "Duplicate filter for column '" + column.name() + "'");
        }
    }

    private void addRange(Map<String, ColumnModel> columns, EntityModel entity,
                          List<QuerySpec.RangeFilter> ranges, Set<String> rangeKeys,
                          int otherFilterCount, QuerySpec.RangeOperator operator,
                          String requestedColumn, String rawValue) {
        ColumnModel column = resolveColumn(columns, entity, requestedColumn);
        if (column.category() != ColumnCategory.NUMBER
                && column.category() != ColumnCategory.DATE
                && column.category() != ColumnCategory.TIMESTAMP) {
            throw new IllegalArgumentException("Range filter is only supported for NUMBER, DATE "
                    + "and TIMESTAMP columns, not '" + column.name() + "'");
        }
        String key = operator.name() + "\u0000" + column.name().toLowerCase(Locale.ROOT);
        if (!rangeKeys.add(key)) {
            throw new IllegalArgumentException(
                    "Duplicate filter for column '" + column.name() + "'");
        }
        ranges.add(new QuerySpec.RangeFilter(column.name(), operator, convert(column, rawValue)));
        if (otherFilterCount + ranges.size() > MAX_FILTERS) {
            throw filterLimit();
        }
    }

    private static String rangePrefix(String name) {
        for (String prefix : RANGE_PREFIXES.keySet()) {
            if (name.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private void addEquals(Map<String, ColumnModel> columns, EntityModel entity,
                           Map<String, Object> equals, Map<String, String> contains,
                           String requestedColumn, String rawValue) {
        ColumnModel column = resolveColumn(columns, entity, requestedColumn);
        if (equals.put(column.name(), convert(column, rawValue)) != null) {
            throw new IllegalArgumentException(
                    "Duplicate filter for column '" + column.name() + "'");
        }
        if (equals.size() + contains.size() > MAX_FILTERS) {
            throw filterLimit();
        }
    }

    private void addContains(Map<String, ColumnModel> columns, EntityModel entity,
                             Map<String, Object> equals, Map<String, String> contains,
                             String requestedColumn, String rawValue) {
        ColumnModel column = resolveColumn(columns, entity, requestedColumn);
        if (column.category() != ColumnCategory.TEXT) {
            throw new IllegalArgumentException(
                    "contains filter is only supported for TEXT columns, not '"
                            + column.name() + "'");
        }
        if (contains.put(column.name(), rawValue) != null) {
            throw new IllegalArgumentException(
                    "Duplicate filter for column '" + column.name() + "'");
        }
        if (equals.size() + contains.size() > MAX_FILTERS) {
            throw filterLimit();
        }
    }

    private Object convert(ColumnModel column, String rawValue) {
        if (rawValue == null) {
            throw conversionFailure(column, null);
        }
        try {
            return switch (column.category()) {
                case NUMBER -> new BigDecimal(rawValue.trim());
                case BOOLEAN -> parseBoolean(column, rawValue.trim());
                case DATE -> LocalDate.parse(rawValue.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                case TIMESTAMP -> parseTimestamp(rawValue.trim());
                case TEXT -> rawValue;
                case OTHER -> throw new IllegalArgumentException(
                        "Column '" + column.name() + "' has unsupported category OTHER");
            };
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw conversionFailure(column, rawValue);
        }
    }

    private Boolean parseBoolean(ColumnModel column, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        throw conversionFailure(column, value);
    }

    /**
     * Parses an ISO-8601 date-time. A value with a UTC offset or {@code Z} is
     * an instant; a value without an offset is a local date-time.
     */
    private Object parseTimestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    private int parseLimit(String rawValue) {
        int value = parseInteger(rawValue, LIMIT);
        if (value < 1 || value > maxPageSize) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + maxPageSize);
        }
        return value;
    }

    private int parseOffset(String rawValue) {
        int value = parseInteger(rawValue, OFFSET);
        if (value < 0 || value > MAX_OFFSET) {
            throw new IllegalArgumentException(
                    "offset must be between 0 and " + MAX_OFFSET);
        }
        return value;
    }

    private static int parseInteger(String rawValue, String name) {
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    private List<QuerySpec.Sort> parseSorts(List<String> tokens,
                                            Map<String, ColumnModel> columns,
                                            EntityModel entity) {
        List<QuerySpec.Sort> sorts = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            QuerySpec.Direction direction = QuerySpec.Direction.ASC;
            String requested = token;
            char prefix = token.charAt(0);
            if (prefix == '-') {
                direction = QuerySpec.Direction.DESC;
                requested = token.substring(1);
            } else if (prefix == '+') {
                requested = token.substring(1);
            }
            sorts.add(new QuerySpec.Sort(
                    resolveColumn(columns, entity, requested).name(), direction));
        }
        return sorts;
    }

    private static ColumnModel resolveColumn(Map<String, ColumnModel> columns,
                                             EntityModel entity, String requested) {
        ColumnModel column = requested == null
                ? null : columns.get(requested.toLowerCase(Locale.ROOT));
        if (column == null) {
            throw new IllegalArgumentException(
                    "Unknown column '" + requested + "' for entity '" + entity.name() + "'");
        }
        return column;
    }

    private static Map<String, ColumnModel> indexColumns(EntityModel entity) {
        Map<String, ColumnModel> byLowerCaseName = new LinkedHashMap<>();
        for (ColumnModel column : entity.columns()) {
            byLowerCaseName.put(column.name().toLowerCase(Locale.ROOT), column);
        }
        return byLowerCaseName;
    }

    private static List<String> splitCsv(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String token : value.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    tokens.add(trimmed);
                }
            }
        }
        return tokens;
    }

    private static String singleValue(String parameter, List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                    "Query parameter '" + parameter + "' requires a value");
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException(
                    "Query parameter '" + parameter + "' may be given only once");
        }
        String value = values.get(0);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Query parameter '" + parameter + "' requires a value");
        }
        return value;
    }

    private static IllegalArgumentException filterLimit() {
        return new IllegalArgumentException("At most " + MAX_FILTERS + " filters are allowed");
    }

    private static IllegalArgumentException conversionFailure(ColumnModel column, String rawValue) {
        return new IllegalArgumentException(
                "Invalid value '" + rawValue + "' for column '" + column.name() + "'");
    }
}
