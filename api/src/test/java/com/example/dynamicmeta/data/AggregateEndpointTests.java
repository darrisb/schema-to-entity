package com.example.dynamicmeta.data;

import com.example.dynamicmeta.SchemaDiscoveryService;
import com.example.dynamicmeta.SchemaToEntityProperties;
import com.example.dynamicmeta.SchemaToEntityProperties.SourceProperties;
import com.example.dynamicmeta.connection.ConnectionPolicy;
import com.example.dynamicmeta.connection.ConnectionStringParser;
import com.example.dynamicmeta.connection.JdbcConnectionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AggregateEndpointTests {

    private static final String URL = "jdbc:h2:mem:ste_aggregate;DB_CLOSE_DELAY=-1";
    private static final ConnectionStringParser PARSER = new ConnectionStringParser();
    private static final ConnectionPolicy POLICY = new ConnectionPolicy(List.of(), true);
    private SourceRegistry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        seed();
        SourceProperties source = new SourceProperties();
        source.setUrl(URL);
        source.setUsername("sa");
        source.setPassword("");
        source.setSchema("PUBLIC");
        source.setHiddenColumns(List.of("secret"));

        SchemaToEntityProperties properties = new SchemaToEntityProperties();
        properties.setSources(Map.of("orders", source));
        properties.setDefaultPageSize(20);
        properties.setMaxPageSize(100);
        properties.setQueryTimeout(Duration.ofSeconds(15));

        registry = new SourceRegistry(DataSourceDefinitions.from(properties, PARSER, POLICY),
                new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60)),
                PARSER, Duration.ofSeconds(10));
        SchemaCache cache = new SchemaCache(registry,
                new SchemaDiscoveryService(null, properties, POLICY,
                        new JdbcConnectionFactory(Duration.ofSeconds(10), Duration.ofSeconds(60))),
                properties);
        DataQueryService service = new DataQueryService(registry, cache,
                new QueryParameterParser(properties), properties);
        mockMvc = MockMvcBuilders.standaloneSetup(new DataController(service)).build();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) registry.destroy();
    }

    @Test
    void countsByStatusAndSumsTotalsWithFilter() throws Exception {
        mockMvc.perform(get("/api/data/orders/sales/aggregate")
                        .param("group", "status")
                        .param("metric", "count,sum:total")
                        .param("eq.region", "east")
                        .param("sort", "status"))
                .andExpect(status().isOk())
                .andExpect(header().string(DataController.SCHEMA_VERSION_HEADER,
                        matchesPattern("[0-9a-f]{12}")))
                .andExpect(jsonPath("$.groups[0]").value("STATUS"))
                .andExpect(jsonPath("$.metrics[0]").value("count"))
                .andExpect(jsonPath("$.metrics[1]").value("sum_total"))
                .andExpect(jsonPath("$.rows[0].STATUS").value("closed"))
                .andExpect(jsonPath("$.rows[0].count").value(1))
                .andExpect(jsonPath("$.rows[0].sum_total").value(5.5))
                .andExpect(jsonPath("$.rows[1].STATUS").value("open"))
                .andExpect(jsonPath("$.rows[1].count").value(2))
                .andExpect(jsonPath("$.rows[1].sum_total").value(30.5));
    }

    @Test
    void sumOnlyWithoutSortReturns200() throws Exception {
        mockMvc.perform(get("/api/data/orders/sales/aggregate")
                        .param("group", "status")
                        .param("metric", "sum:total"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics[0]").value("sum_total"))
                .andExpect(jsonPath("$.rows[0].sum_total").value(129.5))
                .andExpect(jsonPath("$.rows[1].sum_total").value(5.5));
    }

    @Test
    void countColumnWithoutSortReturns200() throws Exception {
        mockMvc.perform(get("/api/data/orders/sales/aggregate")
                        .param("group", "status")
                        .param("metric", "count:region"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics[0]").value("count_region"))
                .andExpect(jsonPath("$.rows[0].count_region").value(3));
    }

    @Test
    void badMetricSyntaxReturns400Error() throws Exception {
        mockMvc.perform(get("/api/data/orders/sales/aggregate")
                        .param("group", "status").param("metric", "sum"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void hiddenGroupColumnReturns400() throws Exception {
        mockMvc.perform(get("/api/data/orders/sales/aggregate")
                        .param("group", "secret").param("metric", "count"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    private static void seed() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(URL);
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("create table sales (id bigint primary key, status varchar(20), "
                    + "region varchar(20), total numeric(10,2), secret varchar(20))");
            statement.execute("insert into sales values "
                    + "(1, 'open', 'east', 10.00, 'a'),"
                    + "(2, 'open', 'east', 20.50, 'b'),"
                    + "(3, 'closed', 'east', 5.50, 'c'),"
                    + "(4, 'open', 'west', 99.00, 'd')");
        }
    }
}
