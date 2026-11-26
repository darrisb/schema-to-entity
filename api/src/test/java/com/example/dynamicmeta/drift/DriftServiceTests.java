package com.example.dynamicmeta.drift;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityAutoConfiguration;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import com.example.dynamicmeta.data.DataSourceDefinitions;
import com.example.dynamicmeta.data.SchemaCache;
import com.example.dynamicmeta.data.SourceNotFoundException;
import com.example.dynamicmeta.data.SourceRegistry;
import com.example.dynamicmeta.graph.OverlayStore;
import com.example.dynamicmeta.pages.Page;
import com.example.dynamicmeta.pages.PageDependencyExtractor;
import com.example.dynamicmeta.pages.PageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DR-004: one drift check combines discovery, the snapshot and the diff. The
 * first check of a source records its baseline, a later structural difference
 * is reported as changed with the classified changes, a reviewer accepts the
 * snapshot they were shown (and only that one), and a source that cannot be
 * discovered is reported as unavailable with a generic reason that never carries
 * the driver's message. DR-005 adds the impact of each change: a changed report
 * names the overlay labels, decisions and manual relationships and the baseline
 * foreign keys that refer to it. These tests read H2 metadata and write snapshots
 * and the overlay file into a temporary folder only: nothing writes to a source
 * database, and a check never edits the overlay.
 */
class DriftServiceTests {

    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final JdbcConnectionFactory FACTORY =
            new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60));
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);

    @TempDir
    Path snapshotDir;

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    @Test
    void theFirstCheckOfASourceRecordsItsBaseline() throws Exception {
        seed("ste_drift_first",
                "create table customers (id bigint primary key, name varchar(80) not null)");
        Fixture fixture = fixture(Map.of("shop", h2Source("ste_drift_first")));

        DriftReport report = fixture.service().check("shop");

        assertThat(report.source()).isEqualTo("shop");
        assertThat(report.status()).isEqualTo(DriftReport.Status.BASELINE_CREATED);
        assertThat(report.isChanged()).isFalse();
        // There was nothing to compare with: the snapshot just taken is the baseline.
        assertThat(report.currentFingerprint()).isEqualTo(report.baselineFingerprint());
        assertThat(report.baselineTakenAt()).isEqualTo(report.checkedAt());
        assertThat(report.changes()).isEmpty();
        assertThat(report.impact()).isEmpty();
        assertThat(report.error()).isNull();

        // The baseline is on disk, and the report is remembered.
        assertThat(snapshotDir.resolve("shop.json")).exists();
        assertThat(fixture.store().baseline("shop"))
                .map(SchemaSnapshot::fingerprint)
                .contains(report.baselineFingerprint());
        assertThat(fixture.service().lastReport("shop")).contains(report);

        fixture.registry().destroy();
    }

    @Test
    void aSecondCheckOfTheSameSchemaIsUnchanged() throws Exception {
        seed("ste_drift_stable", "create table customers (id bigint primary key)");
        Fixture fixture = fixture(Map.of("shop", h2Source("ste_drift_stable")));
        fixture.service().check("shop");

        DriftReport again = fixture.service().check("shop");

        assertThat(again.status()).isEqualTo(DriftReport.Status.UNCHANGED);
        assertThat(again.changes()).isEmpty();
        assertThat(again.currentFingerprint()).isEqualTo(again.baselineFingerprint());

        fixture.registry().destroy();
    }

    @Test
    void addingAColumnAfterTheBaselineIsReportedAsChanged() throws Exception {
        seed("ste_drift_column",
                "create table customers (id bigint primary key, name varchar(80) not null)");
        Fixture fixture = fixture(Map.of("shop", h2Source("ste_drift_column")));
        DriftReport baseline = fixture.service().check("shop");

        seed("ste_drift_column", "alter table customers add email varchar(120)");
        DriftReport changed = fixture.service().check("shop");

        assertThat(changed.status()).isEqualTo(DriftReport.Status.CHANGED);
        assertThat(changed.isChanged()).isTrue();
        assertThat(changed.currentFingerprint()).isNotEqualTo(changed.baselineFingerprint());
        assertThat(changed.baselineFingerprint()).isEqualTo(baseline.currentFingerprint());
        assertThat(changed.baselineTakenAt()).isEqualTo(baseline.checkedAt());
        assertThat(changed.checkedAt()).isAfterOrEqualTo(changed.baselineTakenAt());
        // One impact entry per change, in the same order: nothing has been labelled
        // or decided over a column that was just added, and no baseline key uses it.
        assertThat(changed.impact()).singleElement().satisfies(impact -> {
            assertThat(impact.change()).isEqualTo(changed.changes().getFirst());
            assertThat(impact.isEmpty()).isTrue();
            assertThat(impact.pages()).isEmpty();
        });
        assertThat(changed.error()).isNull();
        assertThat(changed.changes()).singleElement().satisfies(added -> {
            assertThat(added.type()).isEqualTo(SchemaChange.Type.COLUMN_ADDED);
            assertThat(added.severity()).isEqualTo(SchemaChange.Severity.ADDITIVE);
            assertThat(added.entity()).isEqualTo("CUSTOMERS");
            assertThat(added.column()).isEqualTo("EMAIL");
            // H2 spells the declared type "character varying(120)"; the snapshot
            // keeps the type exactly as the driver reported it.
            assertThat(added.after()).endsWith("(120) nullable");
        });
        // A new check only reads the baseline: the accepted baseline on disk is
        // still the original schema until someone accepts the drift.
        assertThat(fixture.store().baseline("shop"))
                .map(SchemaSnapshot::fingerprint)
                .contains(baseline.currentFingerprint());

        fixture.registry().destroy();
    }

    @Test
    void aRemovedColumnUsedByASavedPageIsReportedByTheService() throws Exception {
        seed("ste_drift_page_impact",
                "create table orders (id bigint primary key, total decimal(10,2))");
        SourceRegistry registry = registry(Map.of("shop", h2Source("ste_drift_page_impact")));
        SchemaCache cache = new SchemaCache(registry, discovery(),
                new SchemaToEntityProperties());
        SnapshotStore store = new SnapshotStore(snapshotDir);
        PageStore pageStore = new PageStore(snapshotDir.resolve("pages"));
        pageStore.save(new Page("orders-page", "Orders", null,
                new ObjectMapper().readTree("""
                        [{"id":"orders-table","type":"table","dataSource":{
                          "type":"rest","url":"/api/data/shop/orders",
                          "query":{"fields":"id,total"}}}]
                        """), null, null));
        DriftService service = new DriftService(registry, cache, new SnapshotBuilder(), store,
                new OverlayStore(snapshotDir.resolve("overlay.json")), pageStore,
                new PageDependencyExtractor(), Clock.systemUTC());
        service.check("shop");

        seed("ste_drift_page_impact", "alter table orders drop column total");
        DriftReport changed = service.check("shop");

        assertThat(impactOf(changed, SchemaChange.Type.COLUMN_REMOVED).pages())
                .containsExactly(new PageImpact("orders-page", List.of("orders-table")));
        registry.destroy();
    }

    @Test
    void checkAllCoversEveryConfiguredSourceInNameOrder() throws Exception {
        seed("ste_drift_alpha", "create table customers (id bigint primary key)");
        SourceProperties broken = h2Source("ste_drift_missing");
        // A database that was never created: every connection attempt fails and
        // the driver message names the missing database.
        broken.setUrl("jdbc:h2:mem:ste_drift_missing;DB_CLOSE_DELAY=-1;IFEXISTS=TRUE");
        Map<String, SourceProperties> sources = new LinkedHashMap<>();
        sources.put("broken", broken);
        sources.put("alpha", h2Source("ste_drift_alpha"));
        Fixture fixture = fixture(sources);

        List<DriftReport> reports = fixture.service().checkAll();

        assertThat(reports).extracting(DriftReport::source).containsExactly("alpha", "broken");
        assertThat(reports).extracting(report -> report.status().wireName())
                .containsExactly("baseline-created", "unavailable");
        assertThat(reports.getFirst().changes()).isEmpty();
        assertThat(reports.get(1).error()).isEqualTo("Schema discovery failed");
        assertThat(reports.get(1).toString()).doesNotContain("ste_drift_missing");

        // Every source keeps its own baseline file; the failed one has none.
        assertThat(snapshotDir.resolve("alpha.json")).exists();
        assertThat(snapshotDir.resolve("broken.json")).doesNotExist();

        fixture.registry().destroy();
    }

    // ------------------------------------------------------------------
    // Impact (DR-005)
    // ------------------------------------------------------------------

    @Test
    void aChangedReportShowsWhatEachChangeAffects() throws Exception {
        seed("ste_drift_impact",
                "create table customers (id bigint primary key, email varchar(120))",
                "create table orders (id bigint primary key, customer_id bigint"
                        + " references customers(id))");
        Fixture fixture = fixture(Map.of("shop", h2Source("ste_drift_impact")));
        fixture.service().check("shop");

        // The edits a reviewer made, spelled the way the graph spells its ids,
        // over the table and the column that are about to go.
        OverlayStore overlay = fixture.overlayStore();
        overlay.setLabel("shop:customers.email", "Work address", null);
        overlay.setLabel("shop:customers", "Customers", null);
        overlay.setLabel("shop:orders", "Purchases", null);
        overlay.decide("shop:customers.email->crm:contacts.email", "confirmed");
        overlay.decide("shop:orders.customer_id->shop:customers.id", "confirmed");
        overlay.addManual("shop:orders.customer_id", "shop:customers.email");

        seed("ste_drift_impact", "alter table customers drop column email", "drop table orders");
        DriftReport changed = fixture.service().check("shop");

        assertThat(changed.status()).isEqualTo(DriftReport.Status.CHANGED);
        assertThat(changed.impact()).hasSize(changed.changes().size());

        // H2 reports CUSTOMERS and EMAIL; the overlay ids are lower-cased, and the
        // match still finds them. A column change owns its column only, so the
        // label of the table is not reported, and the declared key on
        // orders.customer_id points at customers.id, not at the dropped column.
        assertThat(impactOf(changed, SchemaChange.Type.COLUMN_REMOVED).labels())
                .containsExactly("shop:customers.email");
        assertThat(impactOf(changed, SchemaChange.Type.COLUMN_REMOVED).decisions())
                .containsExactly("shop:customers.email->crm:contacts.email");
        assertThat(impactOf(changed, SchemaChange.Type.COLUMN_REMOVED).manualRelationships())
                .containsExactly("shop:orders.customer_id->shop:customers.email");
        assertThat(impactOf(changed, SchemaChange.Type.COLUMN_REMOVED).foreignKeys()).isEmpty();

        // An entity change owns everything below it: the table's own label, and the
        // relationship ids that name one of its columns on either side, including
        // the foreign key the baseline declared.
        assertThat(impactOf(changed, SchemaChange.Type.ENTITY_REMOVED).labels())
                .containsExactly("shop:orders");
        assertThat(impactOf(changed, SchemaChange.Type.ENTITY_REMOVED).decisions())
                .containsExactly("shop:orders.customer_id->shop:customers.id");
        assertThat(impactOf(changed, SchemaChange.Type.ENTITY_REMOVED).manualRelationships())
                .containsExactly("shop:orders.customer_id->shop:customers.email");
        assertThat(impactOf(changed, SchemaChange.Type.ENTITY_REMOVED).foreignKeys())
                .containsExactly("shop:orders.customer_id->shop:customers.id");

        // Reporting impact only reads the overlay: every edit is still there, so a
        // reviewer who does not accept the drift can find what they labelled.
        assertThat(overlay.overlay().labels()).hasSize(3);
        assertThat(overlay.overlay().relationshipDecisions()).hasSize(2);
        assertThat(overlay.overlay().manualRelationships()).hasSize(1);

        fixture.registry().destroy();
    }

    // ------------------------------------------------------------------
    // Unavailable sources
    // ------------------------------------------------------------------

    @Test
    void aDiscoveryFailureIsReportedWithoutTheDriverMessage() throws Exception {
        SchemaDiscoveryService discoveryService = mock(SchemaDiscoveryService.class);
        when(discoveryService.inspect(any(), any(), any())).thenThrow(new SQLException(
                "FATAL: password 'sup3rs3cret' authentication failed for user \"billing\""
                        + " for jdbc:postgresql://billing:sup3rs3cret@billing.internal:5432/appdb"));
        Fixture fixture = fixture(discoveryService, Map.of("billing", h2Source("ste_drift_leak")));

        DriftReport report = fixture.service().check("billing");

        assertThat(report.status()).isEqualTo(DriftReport.Status.UNAVAILABLE);
        assertThat(report.error()).isEqualTo(DriftService.DISCOVERY_FAILED);
        assertThat(report.error()).doesNotContain("sup3rs3cret")
                .doesNotContain("billing.internal")
                .doesNotContain("postgresql");
        assertThat(report.toString()).doesNotContain("sup3rs3cret")
                .doesNotContain("billing.internal");
        // Nothing was discovered, so there is no current snapshot to report.
        assertThat(report.currentFingerprint()).isNull();
        assertThat(report.baselineFingerprint()).isNull();
        assertThat(report.baselineTakenAt()).isNull();
        assertThat(report.changes()).isEmpty();
        assertThat(report.impact()).isEmpty();
        assertThat(report.isChanged()).isFalse();
        assertThat(fixture.service().lastReport("billing")).contains(report);
        assertThat(snapshotDir.resolve("billing.json")).doesNotExist();

        fixture.registry().destroy();
    }

    @Test
    void anUnavailableSourceKeepsItsBaselineAndItsLastCheckedSnapshot() throws Exception {
        seed("ste_drift_flaky", "create table customers (id bigint primary key)");
        SchemaDiscoveryService real = discovery();
        SchemaDiscoveryService discoveryService = mock(SchemaDiscoveryService.class);
        AtomicBoolean down = new AtomicBoolean();
        when(discoveryService.inspect(any(), any(), any())).thenAnswer(invocation -> {
            if (down.get()) {
                throw new SQLException("connection to jdbc:postgresql://db.internal:5432/app lost");
            }
            return real.inspect(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
        });
        Fixture fixture = fixture(discoveryService, Map.of("shop", h2Source("ste_drift_flaky")));
        DriftReport baseline = fixture.service().check("shop");

        seed("ste_drift_flaky", "alter table customers add memo varchar(40)");
        DriftReport changed = fixture.service().check("shop");
        assertThat(changed.status()).isEqualTo(DriftReport.Status.CHANGED);

        down.set(true);
        DriftReport unavailable = fixture.service().check("shop");

        assertThat(unavailable.status()).isEqualTo(DriftReport.Status.UNAVAILABLE);
        assertThat(unavailable.error()).isEqualTo(DriftService.DISCOVERY_FAILED);
        assertThat(unavailable.toString()).doesNotContain("db.internal");
        // The accepted baseline is still the one on disk, shown for context.
        assertThat(unavailable.baselineFingerprint()).isEqualTo(baseline.currentFingerprint());
        assertThat(unavailable.currentFingerprint()).isNull();

        // What the reviewer was shown last is still acceptable: a failed check
        // does not replace it.
        fixture.service().accept("shop", changed.currentFingerprint());
        assertThat(fixture.store().baseline("shop"))
                .map(SchemaSnapshot::fingerprint)
                .contains(changed.currentFingerprint());

        down.set(false);
        assertThat(fixture.service().check("shop").status())
                .isEqualTo(DriftReport.Status.UNCHANGED);

        fixture.registry().destroy();
    }

    // ------------------------------------------------------------------
    // Accepting drift
    // ------------------------------------------------------------------

    @Test
    void acceptingTheCheckedFingerprintMakesTheNextCheckUnchanged() throws Exception {
        seed("ste_drift_accept",
                "create table customers (id bigint primary key, name varchar(80) not null)");
        Fixture fixture = fixture(Map.of("shop", h2Source("ste_drift_accept")));
        DriftReport baseline = fixture.service().check("shop");

        seed("ste_drift_accept", "alter table customers add email varchar(120)");
        DriftReport changed = fixture.service().check("shop");
        fixture.service().accept("shop", changed.currentFingerprint());

        DriftReport next = fixture.service().check("shop");

        assertThat(next.status()).isEqualTo(DriftReport.Status.UNCHANGED);
        assertThat(next.changes()).isEmpty();
        assertThat(next.baselineFingerprint()).isEqualTo(changed.currentFingerprint());
        assertThat(next.currentFingerprint()).isEqualTo(changed.currentFingerprint());
        // The superseded baseline is archived rather than lost.
        assertThat(fixture.store().baseline("shop"))
                .map(SchemaSnapshot::fingerprint)
                .contains(changed.currentFingerprint());
        assertThat(snapshotDir.resolve("history").resolve("shop").resolve(stampOf(baseline)))
                .exists();

        fixture.registry().destroy();
    }

    @Test
    void acceptingAFingerprintThatIsNoLongerCurrentIsRefused() throws Exception {
        seed("ste_drift_stale", "create table customers (id bigint primary key)");
        Fixture fixture = fixture(Map.of("shop", h2Source("ste_drift_stale")));
        DriftReport baseline = fixture.service().check("shop");

        seed("ste_drift_stale", "alter table customers add memo varchar(40)");
        DriftReport changed = fixture.service().check("shop");

        // The reviewer is holding the fingerprint of the check before this one.
        assertThatThrownBy(() -> fixture.service().accept("shop", baseline.currentFingerprint()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop")
                .hasMessageContaining("changed since it was checked")
                .hasMessageNotContaining("jdbc:");

        // The refusal changes nothing: the baseline is still the old one and the
        // source is still reported as changed.
        assertThat(fixture.store().baseline("shop"))
                .map(SchemaSnapshot::fingerprint)
                .contains(baseline.currentFingerprint());
        assertThat(fixture.service().check("shop").status()).isEqualTo(DriftReport.Status.CHANGED);

        // The fingerprint the reviewer was actually shown is accepted.
        fixture.service().accept("shop", changed.currentFingerprint());
        assertThat(fixture.service().check("shop").status())
                .isEqualTo(DriftReport.Status.UNCHANGED);

        fixture.registry().destroy();
    }

    @Test
    void acceptingBeforeAnyCheckIsRefusedAndWritesNothing() {
        Fixture fixture = fixture(Map.of("shop", h2Source("ste_drift_never_checked")));

        assertThatThrownBy(() -> fixture.service().accept("shop", "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not been checked for drift yet");
        assertThat(snapshotDir.resolve("shop.json")).doesNotExist();

        fixture.registry().destroy();
    }

    @Test
    void anUnknownSourceIsRejectedBeforeAnythingIsReadOrWritten() throws Exception {
        Fixture fixture = fixture(Map.of("shop", h2Source("ste_drift_unknown")));

        assertThatThrownBy(() -> fixture.service().check("other"))
                .isInstanceOf(SourceNotFoundException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> fixture.service().accept("other", "0".repeat(64)))
                .isInstanceOf(SourceNotFoundException.class)
                .hasMessageContaining("other");
        assertThat(fixture.service().lastReport("other")).isEmpty();
        assertThat(fixture.service().lastReport(null)).isEmpty();
        try (Stream<Path> files = Files.list(snapshotDir)) {
            assertThat(files).isEmpty();
        }

        fixture.registry().destroy();
    }

    // ------------------------------------------------------------------
    // Reporting and registration
    // ------------------------------------------------------------------

    @Test
    void statusIsReportedByItsLowercaseWireName() throws Exception {
        Instant now = Instant.parse("2026-11-25T10:00:00Z");
        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(new DriftReport("shop", DriftReport.Status.BASELINE_CREATED,
                        "a".repeat(64), "a".repeat(64), now, now, List.of(), List.of(), null));

        assertThat(json).contains("\"baseline-created\"");
    }

    @Test
    void driftBeansComeWithTheGraphAndTheSchedulerNeedsAnInterval() {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaToEntityAutoConfiguration.class))
                .withBean(DataSource.class, DriftServiceTests::hostDataSource)
                .withPropertyValues(
                        "schema-to-entity.allow-embedded-databases=true",
                        "schema-to-entity.snapshot-dir=" + snapshotDir,
                        "schema-to-entity.overlay-file=" + snapshotDir.resolve("overlay.json"),
                        "schema-to-entity.sources.shop.url="
                                + "jdbc:h2:mem:ste_drift_beans;DB_CLOSE_DELAY=-1",
                        "schema-to-entity.sources.shop.username=sa");

        // Off by default: neither the data API nor the graph.
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(DriftService.class);
            assertThat(context).doesNotHaveBean(SnapshotStore.class);
            assertThat(context).doesNotHaveBean(DriftCheckScheduler.class);
        });

        // The data API alone is not enough, and neither is the graph alone.
        runner.withPropertyValues("schema-to-entity.data-api-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DriftService.class));
        runner.withPropertyValues("schema-to-entity.graph-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DriftService.class));

        // Both together register the store, the builder and the service; the
        // periodic check stays off until an interval is configured.
        runner.withPropertyValues("schema-to-entity.graph-enabled=true",
                        "schema-to-entity.data-api-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(SnapshotStore.class);
                    assertThat(context).hasSingleBean(SnapshotBuilder.class);
                    assertThat(context).hasSingleBean(DriftService.class);
                    assertThat(context).doesNotHaveBean(DriftCheckScheduler.class);
                });

        // An interval schedules it, and closing the context stops the thread.
        AtomicReference<DriftCheckScheduler> started = new AtomicReference<>();
        runner.withPropertyValues("schema-to-entity.graph-enabled=true",
                        "schema-to-entity.data-api-enabled=true",
                        "schema-to-entity.drift-check-interval=1h")
                .run(context -> {
                    assertThat(context).hasSingleBean(DriftCheckScheduler.class);
                    DriftCheckScheduler scheduler = context.getBean(DriftCheckScheduler.class);
                    assertThat(scheduler.isRunning()).isTrue();
                    started.set(scheduler);
                });
        assertThat(started.get()).isNotNull();
        assertThat(started.get().isRunning()).isFalse();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Everything one check needs, over one temporary snapshot folder. */
    private record Fixture(SourceRegistry registry, SchemaCache cache, SnapshotStore store,
                           OverlayStore overlayStore, DriftService service) {
    }

    private Fixture fixture(Map<String, SourceProperties> sources) {
        return fixture(discovery(), sources);
    }

    private Fixture fixture(SchemaDiscoveryService discoveryService,
                            Map<String, SourceProperties> sources) {
        SourceRegistry registry = registry(sources);
        SchemaCache cache = new SchemaCache(registry, discoveryService,
                new SchemaToEntityProperties());
        SnapshotStore store = new SnapshotStore(snapshotDir);
        OverlayStore overlayStore = new OverlayStore(snapshotDir.resolve("overlay.json"));
        return new Fixture(registry, cache, store, overlayStore,
                new DriftService(registry, cache, new SnapshotBuilder(), store, overlayStore,
                        Clock.systemUTC()));
    }

    private static SourceProperties h2Source(String database) {
        SourceProperties source = new SourceProperties();
        source.setUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        return source;
    }

    private static SourceRegistry registry(Map<String, SourceProperties> sources) {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(sources);
        return new SourceRegistry(
                DataSourceDefinitions.from(properties, PARSER, POLICY),
                FACTORY, PARSER, Duration.ofMillis(500));
    }

    private static SchemaDiscoveryService discovery() {
        return new SchemaDiscoveryService(null, new SchemaToEntityProperties(), POLICY, FACTORY);
    }

    private static DataSource hostDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ste_drift_host;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void seed(String database, String... statements) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** The one impact entry of the report's change of this type. */
    private static ChangeImpact impactOf(DriftReport report, SchemaChange.Type type) {
        List<ChangeImpact> matches = report.impact().stream()
                .filter(impact -> impact.change().type() == type)
                .toList();
        assertThat(matches).as("impact of the change of type %s", type).hasSize(1);
        return matches.getFirst();
    }

    /** The history file name the store gives a snapshot: its takenAt, UTC. */
    private static String stampOf(DriftReport report) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(report.checkedAt()) + ".json";
    }
}
