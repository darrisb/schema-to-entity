package com.example.dynamicmeta.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionPolicyTests {

    private static final ConnectionPolicy DEFAULT = new ConnectionPolicy(List.of(), false);
    private static final ConnectionPolicy EMBEDDED_OK = new ConnectionPolicy(List.of(), true);

    private static ParsedConnection withParam(DatabaseType type, String paramKey, String paramValue) {
        Map<String, String> params = Map.of(paramKey, paramValue);
        return new ParsedConnection(type, "jdbc:placeholder://host/db", "host", 5432, "db",
                null, null, params);
    }

    private static ConnectionRejectedException assertRejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected ConnectionRejectedException but nothing was thrown");
        } catch (ConnectionRejectedException ex) {
            return ex;
        } catch (AssertionError ex) {
            throw ex;
        } catch (Throwable t) {
            throw new AssertionError("Expected ConnectionRejectedException but got " + t.getClass().getName(), t);
        }
    }

    // -------------------------------------------------------------------
    // Allowed URL parameters pass
    // -------------------------------------------------------------------

    @Test
    void allowsPostgresSafeParameters() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.POSTGRESQL, "jdbc:postgresql://host/db",
                "host", 5432, "db");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ssl", "true");
        params.put("sslmode", "require");
        params.put("connectTimeout", "10");
        params.put("socketTimeout", "30");
        params.put("ApplicationName", "myapp");
        params.put("currentSchema", "public");
        ParsedConnection withParams = new ParsedConnection(
                pc.databaseType(), pc.jdbcUrl(), pc.host(), pc.port(),
                pc.database(), pc.username(), pc.password(), params);

        assertThatCode(() -> DEFAULT.validate(withParams)).doesNotThrowAnyException();
    }

    @Test
    void allowsMySQLSafeParameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("useSSL", "false");
        params.put("sslMode", "REQUIRED");
        params.put("requireSSL", "true");
        params.put("connectTimeout", "5000");
        params.put("socketTimeout", "10000");
        params.put("serverTimezone", "UTC");
        params.put("characterEncoding", "utf8");
        ParsedConnection pc = new ParsedConnection(DatabaseType.MYSQL,
                "jdbc:mysql://host/db", "host", 3306, "db", null, null, params);

        assertThatCode(() -> DEFAULT.validate(pc)).doesNotThrowAnyException();
    }

    @Test
    void allowsSqlServerSafeParameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("encrypt", "true");
        params.put("trustServerCertificate", "true");
        params.put("databaseName", "reportdb");
        params.put("loginTimeout", "30");
        params.put("applicationName", "myapp");
        ParsedConnection pc = new ParsedConnection(DatabaseType.SQLSERVER,
                "jdbc:sqlserver://host;databaseName=reportdb", "host", 1433, "reportdb",
                null, null, params);

        assertThatCode(() -> DEFAULT.validate(pc)).doesNotThrowAnyException();
    }

    @Test
    void credentialParametersAreNotRejected() {
        // The parser removes user/password parameters before the policy sees
        // them (they become connection properties), so they are no longer
        // rejectable URL parameters.
        ParsedConnection parsed = new ConnectionStringParser().parse(
                "jdbc:postgresql://host/db?user=u&password=p");
        assertThat(parsed.parameters()).doesNotContainKeys("user", "password");
        assertThatCode(() -> DEFAULT.validate(parsed)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------
    // Dangerous parameters rejected per database type
    // -------------------------------------------------------------------

    @Test
    void rejectsPostgresSocketFactory() {
        ParsedConnection pc = withParam(DatabaseType.POSTGRESQL, "socketFactory", "com.evil.Factory");
        ConnectionRejectedException ex = assertRejects(() -> DEFAULT.validate(pc));
        assertThat(ex.getMessage()).contains("socketFactory").doesNotContain("com.evil");
    }

    @Test
    void rejectsPostgresSslFactory() {
        ParsedConnection pc = withParam(DatabaseType.POSTGRESQL, "sslfactory", "com.evil.Factory");
        ConnectionRejectedException ex = assertRejects(() -> DEFAULT.validate(pc));
        assertThat(ex.getMessage()).contains("sslfactory").doesNotContain("evil");
    }

    @Test
    void rejectsPostgresLoggerFile() {
        ParsedConnection pc = withParam(DatabaseType.POSTGRESQL, "loggerFile", "/tmp/evil.log");
        ConnectionRejectedException ex = assertRejects(() -> DEFAULT.validate(pc));
        assertThat(ex.getMessage()).contains("loggerFile").doesNotContain("evil.log");
    }

    @Test
    void rejectsMySqlAllowLoadLocalInfile() {
        ParsedConnection pc = withParam(DatabaseType.MYSQL, "allowLoadLocalInfile", "true");
        assertThatThrownBy(() -> DEFAULT.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining("allowLoadLocalInfile");
    }

    @Test
    void rejectsMySqlAutoDeserialize() {
        ParsedConnection pc = withParam(DatabaseType.MYSQL, "autoDeserialize", "true");
        assertThatThrownBy(() -> DEFAULT.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining("autoDeserialize");
    }

    @Test
    void rejectsMySqlQueryInterceptors() {
        ParsedConnection pc = withParam(DatabaseType.MYSQL, "queryInterceptors", "com.evil.Interceptor");
        ConnectionRejectedException ex = assertRejects(() -> DEFAULT.validate(pc));
        assertThat(ex.getMessage()).contains("queryInterceptors").doesNotContain("evil");
    }

    @Test
    void rejectsH2InitRunscript() {
        Map<String, String> params = Map.of("INIT", "RUNSCRIPT FROM 'file:/etc/passwd'");
        ParsedConnection pc = new ParsedConnection(DatabaseType.H2, "jdbc:placeholder://host/db",
                null, null, "db", null, null, params);
        ConnectionRejectedException ex = assertRejects(() -> EMBEDDED_OK.validate(pc));
        assertThat(ex.getMessage()).contains("INIT").doesNotContain("passwd");
    }

    @Test
    void rejectsOracleAnyParameter() {
        ParsedConnection pc = withParam(DatabaseType.ORACLE, "someParam", "value");
        ConnectionRejectedException ex = assertRejects(() -> DEFAULT.validate(pc));
        assertThat(ex.getMessage()).contains("someParam").doesNotContain("value");
    }

    @Test
    void rejectsUnknownParameterOnPostgres() {
        ParsedConnection pc = withParam(DatabaseType.POSTGRESQL, "unknownDangerousParam", "x");
        ConnectionRejectedException ex = assertRejects(() -> DEFAULT.validate(pc));
        assertThat(ex.getMessage()).contains("unknownDangerousParam").doesNotContain("x");
    }

    // -------------------------------------------------------------------
    // Host allowlist
    // -------------------------------------------------------------------

    @Test
    void hostAllowlistExactMatch() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.POSTGRESQL, "jdbc:postgresql://db.example.com/db",
                "db.example.com", 5432, "db");
        ConnectionPolicy policy = new ConnectionPolicy(List.of("db.example.com"), false);
        assertThatCode(() -> policy.validate(pc)).doesNotThrowAnyException();
    }

    @Test
    void hostAllowlistWildcardMatch() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.POSTGRESQL, "jdbc:postgresql://sub.example.com/db",
                "sub.example.com", 5432, "db");
        ConnectionPolicy policy = new ConnectionPolicy(List.of("*.example.com"), false);
        assertThatCode(() -> policy.validate(pc)).doesNotThrowAnyException();
    }

    @Test
    void hostAllowlistMismatch() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.POSTGRESQL, "jdbc:postgresql://evil.com/db",
                "evil.com", 5432, "db");
        ConnectionPolicy policy = new ConnectionPolicy(List.of("db.example.com"), false);
        assertThatThrownBy(() -> policy.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining("evil.com");
    }

    @Test
    void wildcardDoesNotMatchBareDomain() {
        // *.example.com should NOT match example.com itself
        ParsedConnection pc = ParsedConnection.of(DatabaseType.POSTGRESQL, "jdbc:postgresql://example.com/db",
                "example.com", 5432, "db");
        ConnectionPolicy policy = new ConnectionPolicy(List.of("*.example.com"), false);
        assertThatThrownBy(() -> policy.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining("example.com");
    }

    @Test
    void emptyAllowlistPermitsAnyHost() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.POSTGRESQL, "jdbc:postgresql://anyhost/db",
                "anyhost", 5432, "db");
        ConnectionPolicy policy = new ConnectionPolicy(List.of(), false);
        assertThatCode(() -> policy.validate(pc)).doesNotThrowAnyException();
    }

    @Test
    void nullHostRejectedByNonEmptyAllowlist() {
        // H2 mem with non-empty allowed-hosts: no host to match
        ParsedConnection pc = ParsedConnection.of(DatabaseType.H2, "jdbc:h2:mem:test", null, null, "test");
        ConnectionPolicy policy = new ConnectionPolicy(List.of("somehost"), true);
        assertThatThrownBy(() -> policy.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining("no host provided");
    }

    // -------------------------------------------------------------------
    // Metadata / link-local addresses always rejected
    // -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "169.254.1.1",
            "169.254.0.0",
            "169.254.255.255",
            "metadata.google.internal",
            "fd00:ec2::254"
    })
    void metadataAndLinkLocalRejectedWithEmptyAllowlist(String host) {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.POSTGRESQL, "jdbc:postgresql://host/db",
                host, 5432, "db");
        assertThatThrownBy(() -> DEFAULT.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining(host);
    }

    @Test
    void metadataRejectedEvenWhenAllowedInHostList() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.POSTGRESQL,
                "jdbc:postgresql://metadata.google.internal/db",
                "metadata.google.internal", 5432, "db");
        ConnectionPolicy policy = new ConnectionPolicy(
                List.of("metadata.google.internal"), false);
        assertThatThrownBy(() -> policy.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining("metadata.google.internal");
    }

    // -------------------------------------------------------------------
    // H2 / embedded database rejection
    // -------------------------------------------------------------------

    @Test
    void h2MemRejectedByDefault() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.H2, "jdbc:h2:mem:test",
                null, null, "test");
        assertThatThrownBy(() -> DEFAULT.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining("Embedded");
    }

    @Test
    void h2FileRejectedByDefault() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.H2, "jdbc:h2:file:/data/mydb",
                null, null, "/data/mydb");
        assertThatThrownBy(() -> DEFAULT.validate(pc))
                .isInstanceOf(ConnectionRejectedException.class)
                .hasMessageContaining("Embedded");
    }

    @Test
    void h2AcceptedWhenAllowEmbeddedDatabasesTrue() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.H2, "jdbc:h2:mem:test",
                null, null, "test");
        assertThatCode(() -> EMBEDDED_OK.validate(pc)).doesNotThrowAnyException();
    }

    @Test
    void h2TcpWithHostIsNotTreatedAsEmbedded() {
        ParsedConnection pc = ParsedConnection.of(DatabaseType.H2,
                "jdbc:h2:tcp://h2server:9092/~/testdb",
                "h2server", 9092, "/~/testdb");
        // Has a host, so it's not considered embedded
        assertThatCode(() -> DEFAULT.validate(pc)).doesNotThrowAnyException();
    }
}
