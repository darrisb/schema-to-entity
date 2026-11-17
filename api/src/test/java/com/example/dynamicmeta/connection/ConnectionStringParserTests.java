package com.example.dynamicmeta.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionStringParserTests {

    private final ConnectionStringParser parser = new ConnectionStringParser();

    // -----------------------------------------------------------------
    // JDBC URLs — PostgreSQL
    // -----------------------------------------------------------------

    @Test
    void parsesPostgresJdbcWithHostPortDatabase() {
        ParsedConnection p = parser.parse("jdbc:postgresql://dbhost:5432/appdb");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:postgresql://dbhost:5432/appdb");
        assertThat(p.host()).isEqualTo("dbhost");
        assertThat(p.port()).isEqualTo(5432);
        assertThat(p.database()).isEqualTo("appdb");
        assertThat(p.username()).isNull();
        assertThat(p.password()).isNull();
    }

    @Test
    void parsesPostgresJdbcWithQueryParams() {
        ParsedConnection p = parser.parse("jdbc:postgresql://localhost:5432/testdb?ssl=true&connectTimeout=10");
        assertThat(p.host()).isEqualTo("localhost");
        assertThat(p.port()).isEqualTo(5432);
        assertThat(p.database()).isEqualTo("testdb");
        assertThat(p.parameters()).containsEntry("ssl", "true").containsEntry("connectTimeout", "10");
    }

    @Test
    void parsesPostgresJdbcWithNoPort() {
        ParsedConnection p = parser.parse("jdbc:postgresql://localhost/mydb");
        assertThat(p.host()).isEqualTo("localhost");
        assertThat(p.port()).isNull();
        assertThat(p.database()).isEqualTo("mydb");
    }

    // -----------------------------------------------------------------
    // JDBC URLs — MySQL
    // -----------------------------------------------------------------

    @Test
    void parsesMySqlJdbc() {
        ParsedConnection p = parser.parse("jdbc:mysql://mysql-host:3306/sales?useSSL=false");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.MYSQL);
        assertThat(p.host()).isEqualTo("mysql-host");
        assertThat(p.port()).isEqualTo(3306);
        assertThat(p.database()).isEqualTo("sales");
        assertThat(p.parameters()).containsEntry("useSSL", "false");
    }

    @Test
    void parsesMySqlJdbcNoPortNoDatabase() {
        ParsedConnection p = parser.parse("jdbc:mysql://mysql-host");
        assertThat(p.host()).isEqualTo("mysql-host");
        assertThat(p.port()).isNull();
        assertThat(p.database()).isNull();
    }

    // -----------------------------------------------------------------
    // JDBC URLs — MariaDB
    // -----------------------------------------------------------------

    @Test
    void parsesMariaDbJdbc() {
        ParsedConnection p = parser.parse("jdbc:mariadb://mariadb-host:3307/inventory");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.MARIADB);
        assertThat(p.host()).isEqualTo("mariadb-host");
        assertThat(p.port()).isEqualTo(3307);
        assertThat(p.database()).isEqualTo("inventory");
    }

    // -----------------------------------------------------------------
    // JDBC URLs — SQL Server
    // -----------------------------------------------------------------

    @Test
    void parsesSqlServerJdbcWithSemicolons() {
        ParsedConnection p = parser.parse("jdbc:sqlserver://sql-host:1433;databaseName=reportdb;encrypt=true");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.SQLSERVER);
        assertThat(p.host()).isEqualTo("sql-host");
        assertThat(p.port()).isEqualTo(1433);
        assertThat(p.database()).isEqualTo("reportdb");
        assertThat(p.parameters()).containsEntry("encrypt", "true");
    }

    @Test
    void parsesSqlServerJdbcWithNoParams() {
        ParsedConnection p = parser.parse("jdbc:sqlserver://sql-host");
        assertThat(p.host()).isEqualTo("sql-host");
        assertThat(p.port()).isNull();
        assertThat(p.database()).isNull();
    }

    // -----------------------------------------------------------------
    // JDBC URLs — Oracle
    // -----------------------------------------------------------------

    @Test
    void parsesOracleServiceName() {
        ParsedConnection p = parser.parse("jdbc:oracle:thin:@//ora-host:1521/myservice");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.ORACLE);
        assertThat(p.host()).isEqualTo("ora-host");
        assertThat(p.port()).isEqualTo(1521);
        assertThat(p.database()).isEqualTo("/myservice");
    }

    @Test
    void parsesOracleSid() {
        ParsedConnection p = parser.parse("jdbc:oracle:thin:@ora-host:1521:orcl");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.ORACLE);
        assertThat(p.host()).isEqualTo("ora-host");
        assertThat(p.port()).isEqualTo(1521);
        assertThat(p.database()).isEqualTo("orcl");
    }

    // -----------------------------------------------------------------
    // JDBC URLs — H2
    // -----------------------------------------------------------------

    @Test
    void parsesH2Mem() {
        ParsedConnection p = parser.parse("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.H2);
        assertThat(p.host()).isNull();
        assertThat(p.port()).isNull();
        assertThat(p.database()).isEqualTo("testdb");
        assertThat(p.parameters()).containsEntry("DB_CLOSE_DELAY", "-1");
    }

    @Test
    void parsesH2File() {
        ParsedConnection p = parser.parse("jdbc:h2:file:/data/mydb");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.H2);
        assertThat(p.database()).isEqualTo("/data/mydb");
    }

    @Test
    void parsesH2Tcp() {
        ParsedConnection p = parser.parse("jdbc:h2:tcp://remotehost:9092/~/testdb");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.H2);
        assertThat(p.host()).isEqualTo("remotehost");
        assertThat(p.port()).isEqualTo(9092);
        assertThat(p.database()).isEqualTo("/~/testdb");
    }

    // -----------------------------------------------------------------
    // URI forms
    // -----------------------------------------------------------------

    @Test
    void parsesPostgresUri() {
        ParsedConnection p = parser.parse("postgres://user:p%40sswd@pg-host:5432/mydb?sslmode=require");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(p.host()).isEqualTo("pg-host");
        assertThat(p.port()).isEqualTo(5432);
        assertThat(p.database()).isEqualTo("mydb");
        assertThat(p.username()).isEqualTo("user");
        assertThat(p.password()).isEqualTo("p@sswd");
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:postgresql://pg-host:5432/mydb?sslmode=require");
    }

    @Test
    void parsesPostgresqlUriAlias() {
        ParsedConnection p = parser.parse("postgresql://pg-host/app");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(p.database()).isEqualTo("app");
    }

    @Test
    void parsesMySqlUri() {
        ParsedConnection p = parser.parse("mysql://root:pass@mysql-host:3306/testdb");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.MYSQL);
        assertThat(p.username()).isEqualTo("root");
        assertThat(p.password()).isEqualTo("pass");
        assertThat(p.database()).isEqualTo("testdb");
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:mysql://mysql-host:3306/testdb");
    }

    @Test
    void parsesMariaDbUri() {
        ParsedConnection p = parser.parse("mariadb://user:mypass@mariadb-host/inventory");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.MARIADB);
        assertThat(p.username()).isEqualTo("user");
        assertThat(p.password()).isEqualTo("mypass");
        assertThat(p.database()).isEqualTo("inventory");
    }

    @Test
    void parsesSqlServerUri() {
        ParsedConnection p = parser.parse("sqlserver://admin@sql-host:1433/reportdb?encrypt=true&trustServerCertificate=true");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.SQLSERVER);
        assertThat(p.host()).isEqualTo("sql-host");
        assertThat(p.port()).isEqualTo(1433);
        assertThat(p.database()).isEqualTo("reportdb");
        assertThat(p.username()).isEqualTo("admin");
        assertThat(p.parameters()).hasSize(2);
        // SQL Server uses semicolons in JDBC URL
        String jdbcUrl = p.jdbcUrl();
        assertThat(jdbcUrl).contains(";databaseName=reportdb");
        assertThat(jdbcUrl).contains(";encrypt=true");
        assertThat(jdbcUrl).contains(";trustServerCertificate=true");
    }

    @Test
    void parsesUriWithPlusInPassword() {
        // '+' in URI userInfo should be decoded as literal '+', not as space
        ParsedConnection p = parser.parse("postgres://user:pa%2Bss@host/db");
        assertThat(p.password()).isEqualTo("pa+ss");
    }

    @Test
    void parsesUriWithUsernameOnly() {
        ParsedConnection p = parser.parse("postgres://reader@host/db");
        assertThat(p.username()).isEqualTo("reader");
        assertThat(p.password()).isNull();
    }

    // -----------------------------------------------------------------
    // Rejection cases
    // -----------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {"  ", "\t\n"})
    void rejectsNullOrBlankInput(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectionString is required");
    }

    @Test
    void rejectsBlankInputWithOnlyWhitespace() {
        assertThatThrownBy(() -> parser.parse("   \t\n  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mongodb://localhost:27017/mydb",
            "mongodb+srv://user:pass@cluster0.mongodb.net/mydb"
    })
    void rejectsMongoDbConnectionStrings(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(UnsupportedDatabaseException.class)
                .hasMessageContaining("MongoDB");
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> parser.parse("redis://localhost:6379"))
                .isInstanceOf(UnsupportedDatabaseException.class);
    }

    @Test
    void rejectsUnsupportedJdbcPrefix() {
        assertThatThrownBy(() -> parser.parse("jdbc:sqlite:/path/to/db"))
                .isInstanceOf(UnsupportedDatabaseException.class);
    }

    @Test
    void rejectsUnrecognisedFormat() {
        assertThatThrownBy(() -> parser.parse("host:5432/mydb"))
                .isInstanceOf(UnsupportedDatabaseException.class);
    }

    // -----------------------------------------------------------------
    // FX-001: strict parser — verified parameter-smuggling bypasses
    // -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            // query hidden in the authority because there is no /database path
            "jdbc:postgresql://db.example.com:5432?socketFactory=x",
            // missing // authority separator
            "jdbc:postgresql:app?socketFactory=x",
            // MySQL address=(...) sub-protocol form
            "jdbc:mysql://address=(host=h)(port=3306)(allowLoadLocalInfile=true)/app",
            // MySQL (host=...,param=...) multi-host form
            "jdbc:mysql://(host=h,autoDeserialize=true)/app",
            // Oracle TNS descriptor
            "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=169.254.169.254))(CONNECT_DATA=(SERVICE_NAME=orcl)))",
            // Oracle query parameters are never parsed
            "jdbc:oracle:thin:@//h:1521/svc?anything=x"
    })
    void rejectsVerifiedParameterSmugglingBypasses(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql:loadbalance://host:3306/db",
            "jdbc:mysql:replication://host:3306/db",
            "jdbc:mysql:srv://host:3306/db",
            "jdbc:mariadb:loadbalance://host:3306/db",
            "jdbc:mariadb:replication://host:3306/db"
    })
    void rejectsMySqlSubProtocols(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://host1:3306,host2:3306/db",
            "jdbc:mariadb://host1,host2/db",
            "jdbc:postgresql://host1,host2/db"
    })
    void rejectsMultiHostLists(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(input);
    }

    @Test
    void rejectsQueryParametersWithoutDatabasePath() {
        assertThatThrownBy(() -> parser.parse("jdbc:postgresql://host:5432?ssl=true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("ssl=true");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://bad_host:5432/db",
            "jdbc:mysql://ho st:3306/db",
            "jdbc:mysql://host(1):3306/db",
            "jdbc:mariadb://host@evil/db",
            "jdbc:sqlserver://ho st/db"
    })
    void rejectsInvalidHostCharacters(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://host:0/db",
            "jdbc:postgresql://host:65536/db",
            "jdbc:postgresql://host:-1/db",
            "jdbc:postgresql://host:abc/db",
            "jdbc:mysql://host:99999/db"
    })
    void rejectsInvalidPorts(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://host/db?ssl%66actory=x",
            "jdbc:mysql://host/db?%61utoDeserialize=true",
            "jdbc:sqlserver://host;data%62aseName=db",
            "jdbc:mariadb://host/db?%zz=x"
    })
    void rejectsPercentEncodedParameterKeys(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:oracle:thin:@MYDB",
            "jdbc:oracle:thin:@//host/service",
            "jdbc:oracle:thin:@host:1521",
            "jdbc:oracle:thin:@host:abc:orcl",
            "jdbc:oracle:thin:@host:1521:",
            "jdbc:oracle:thin:@//host:1521/"
    })
    void rejectsInvalidOracleForms(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(input);
    }

    @Test
    void rejectionMessageDoesNotLeakTheConnectionString() {
        String url = "jdbc:postgresql://host:5432/db?%70assword=hunter2";
        assertThatThrownBy(() -> parser.parse(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(url)
                .hasMessageNotContaining("hunter2");
    }

    // -----------------------------------------------------------------
    // FX-001: previously supported simple forms still parse
    // -----------------------------------------------------------------

    @Test
    void parsesSqlServerInstanceName() {
        ParsedConnection p = parser.parse(
                "jdbc:sqlserver://sql-host\\SQLEXPRESS:1433;databaseName=reportdb");
        assertThat(p.databaseType()).isEqualTo(DatabaseType.SQLSERVER);
        assertThat(p.host()).isEqualTo("sql-host");
        assertThat(p.port()).isEqualTo(1433);
        assertThat(p.database()).isEqualTo("reportdb");
    }

    @Test
    void parsesIpv4Host() {
        ParsedConnection p = parser.parse("jdbc:postgresql://192.168.1.10:5432/appdb");
        assertThat(p.host()).isEqualTo("192.168.1.10");
        assertThat(p.port()).isEqualTo(5432);
        assertThat(p.database()).isEqualTo("appdb");
    }

    @Test
    void parsesBracketedIpv6Host() {
        ParsedConnection p = parser.parse("jdbc:postgresql://[::1]:5432/appdb");
        assertThat(p.host()).isEqualTo("[::1]");
        assertThat(p.port()).isEqualTo(5432);
        assertThat(p.database()).isEqualTo("appdb");
    }

    @Test
    void parsesOracleSidWithIpv6Host() {
        ParsedConnection p = parser.parse("jdbc:oracle:thin:@[::1]:1521:orcl");
        assertThat(p.host()).isEqualTo("[::1]");
        assertThat(p.port()).isEqualTo(1521);
        assertThat(p.database()).isEqualTo("orcl");
    }

    // -----------------------------------------------------------------
    // DatabaseType enum
    // -----------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "POSTGRESQL, postgresql",
            "MYSQL, mysql",
            "MARIADB, mariadb",
            "SQLSERVER, sqlserver",
            "ORACLE, oracle",
            "H2, h2"
    })
    void databaseTypeIdMatchesExpected(DatabaseType type, String expected) {
        assertThat(type.id()).isEqualTo(expected);
    }

    @Test
    void databaseTypeFromIdIsCaseInsensitive() {
        assertThat(DatabaseType.fromId("PostgreSQL")).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(DatabaseType.fromId("MYSQL")).isEqualTo(DatabaseType.MYSQL);
        assertThat(DatabaseType.fromId("h2")).isEqualTo(DatabaseType.H2);
    }

    @Test
    void databaseTypeFromIdReturnsNullForUnknown() {
        assertThat(DatabaseType.fromId("unknown")).isNull();
    }

    // -----------------------------------------------------------------
    // ParsedConnection toString does not leak password
    // -----------------------------------------------------------------

    @Test
    void toStringDoesNotExposePassword() {
        ParsedConnection p = parser.parse("postgres://user:secret@host/db");
        String str = p.toString();
        assertThat(str).contains("password=***");
        assertThat(str).doesNotContain("secret");
    }

    // -----------------------------------------------------------------
    // FX-002: canonical JDBC URL rebuilt from validated parts
    // -----------------------------------------------------------------

    @Test
    void rebuildsPostgresJdbcUrlFromParts() {
        ParsedConnection p = parser.parse(
                "jdbc:postgresql://dbhost:5432/appdb?ssl=true&connectTimeout=10");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:postgresql://dbhost:5432/appdb?ssl=true&connectTimeout=10");
    }

    @Test
    void rebuildsMySqlJdbcUrlFromParts() {
        ParsedConnection p = parser.parse("jdbc:mysql://mysql-host:3306/sales?useSSL=false");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:mysql://mysql-host:3306/sales?useSSL=false");
    }

    @Test
    void rebuildsMariaDbJdbcUrlFromParts() {
        ParsedConnection p = parser.parse("jdbc:mariadb://mariadb-host:3307/inventory");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:mariadb://mariadb-host:3307/inventory");
    }

    @Test
    void rebuildsSqlServerJdbcUrlFromParts() {
        ParsedConnection p = parser.parse(
                "jdbc:sqlserver://sql-host:1433;databaseName=reportdb;encrypt=true");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:sqlserver://sql-host:1433;databaseName=reportdb;encrypt=true");
    }

    @Test
    void rebuildsOracleServiceJdbcUrl() {
        ParsedConnection p = parser.parse("jdbc:oracle:thin:@//ora-host:1521/myservice");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:oracle:thin:@//ora-host:1521/myservice");
    }

    @Test
    void rebuildsOracleSidJdbcUrl() {
        ParsedConnection p = parser.parse("jdbc:oracle:thin:@ora-host:1521:orcl");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:oracle:thin:@ora-host:1521:orcl");
    }

    @Test
    void rebuildsH2JdbcUrlsPreservingSubProtocol() {
        assertThat(parser.parse("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1").jdbcUrl())
                .isEqualTo("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        assertThat(parser.parse("jdbc:h2:file:/data/mydb").jdbcUrl())
                .isEqualTo("jdbc:h2:file:/data/mydb");
        assertThat(parser.parse("jdbc:h2:tcp://remotehost:9092/~/testdb").jdbcUrl())
                .isEqualTo("jdbc:h2:tcp://remotehost:9092/~/testdb");
    }

    @Test
    void acceptsUserAndPasswordUrlParameters() {
        ParsedConnection p = parser.parse("jdbc:postgresql://h:5432/app?user=u&password=p");
        assertThat(p.username()).isEqualTo("u");
        assertThat(p.password()).isEqualTo("p");
        assertThat(p.parameters()).doesNotContainKeys("user", "password");
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:postgresql://h:5432/app");
    }

    @Test
    void acceptsCredentialParametersCaseInsensitivelyAndKeepsOtherParamsInOrder() {
        ParsedConnection p = parser.parse(
                "jdbc:postgresql://h/app?ssl=true&USER=alice&applicationName=app&PASSWORD=s3cret");
        assertThat(p.username()).isEqualTo("alice");
        assertThat(p.password()).isEqualTo("s3cret");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:postgresql://h/app?ssl=true&applicationName=app");
    }

    @Test
    void acceptsSqlServerCredentialParameters() {
        ParsedConnection p = parser.parse(
                "jdbc:sqlserver://h:1433;databaseName=db;user=u;password=p;encrypt=true");
        assertThat(p.username()).isEqualTo("u");
        assertThat(p.password()).isEqualTo("p");
        assertThat(p.parameters()).doesNotContainKeys("user", "password");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:sqlserver://h:1433;databaseName=db;encrypt=true");
    }

    @Test
    void decodesAndSelectivelyReencodesParameterValues() {
        ParsedConnection p = parser.parse(
                "jdbc:postgresql://h/db?applicationName=my%20app&currentSchema=a/b");
        assertThat(p.parameters())
                .containsEntry("applicationName", "my app")
                .containsEntry("currentSchema", "a/b");
        // Space is escaped as %20, but sub-delimiters such as '/' are left
        // intact so the driver sees the original value.
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:postgresql://h/db?applicationName=my%20app&currentSchema=a/b");
    }

    @Test
    void keepsSubDelimitersInParameterValues() {
        ParsedConnection p = parser.parse(
                "jdbc:mysql://h/db?serverTimezone=Europe/Paris&applicationName=s1,s2");
        assertThat(p.parameters())
                .containsEntry("serverTimezone", "Europe/Paris")
                .containsEntry("applicationName", "s1,s2");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:mysql://h/db?serverTimezone=Europe/Paris&applicationName=s1,s2");
    }

    @Test
    void parameterValuesCannotInjectExtraParameters() {
        // '?'/'&' form: '&', '=' and '#' must be escaped; ';' is not a
        // separator in this form, so it stays literal.
        ParsedConnection p = parser.parse(
                "jdbc:postgresql://h/db?ssl=true&applicationName=x%26y%3Bz%3Dw%23v");
        assertThat(p.parameters())
                .containsEntry("ssl", "true")
                .containsEntry("applicationName", "x&y;z=w#v")
                .hasSize(2);
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:postgresql://h/db?ssl=true&applicationName=x%26y;z%3Dw%23v");
        // The rebuilt URL parses back to exactly the same parameter map.
        assertThat(parser.parse(p.jdbcUrl()).parameters()).isEqualTo(p.parameters());

        // ';' form: ';', '=' and '%' must be escaped.
        ParsedConnection s = parser.parse(
                "jdbc:sqlserver://h:1433;databaseName=db;applicationName=x%3By%3Dw%26v%23z");
        assertThat(s.parameters())
                .containsEntry("databaseName", "db")
                .containsEntry("applicationName", "x;y=w&v#z");
        assertThat(s.jdbcUrl())
                .isEqualTo("jdbc:sqlserver://h:1433;databaseName=db;applicationName=x%3By%3Dw&v#z");
        assertThat(parser.parse(s.jdbcUrl()).parameters()).isEqualTo(s.parameters());
    }

    @Test
    void parsesH2UserAndPasswordParameters() {
        ParsedConnection p = parser.parse("jdbc:h2:mem:t;USER=sa;PASSWORD=pw");
        assertThat(p.username()).isEqualTo("sa");
        assertThat(p.password()).isEqualTo("pw");
        assertThat(p.parameters()).doesNotContainKeys("USER", "PASSWORD").isEmpty();
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:h2:mem:t");
    }

    @Test
    void treatsPlusAsLiteralInParameterValues() {
        ParsedConnection p = parser.parse("jdbc:postgresql://h/db?user=a+b&password=p%40ss");
        assertThat(p.username()).isEqualTo("a+b");
        assertThat(p.password()).isEqualTo("p@ss");
    }

    @Test
    void toleratesMalformedPercentEscapesInParameterValues() {
        ParsedConnection p = parser.parse("jdbc:postgresql://h/db?password=ab%zz");
        assertThat(p.password()).isEqualTo("ab%zz");
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:postgresql://h/db");

        ParsedConnection trailing = parser.parse("jdbc:postgresql://h/db?password=100%");
        assertThat(trailing.password()).isEqualTo("100%");
    }

    @Test
    void toleratesInvalidUtf8PercentEscapesInParameterValues() {
        ParsedConnection p = parser.parse("jdbc:postgresql://h/db?password=ab%cd");
        assertThat(p.password()).isEqualTo("ab%cd");
    }

    @Test
    void preservesParameterInputOrder() {
        ParsedConnection p = parser.parse(
                "jdbc:postgresql://h/db?sslmode=require&ssl=true&applicationName=app");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:postgresql://h/db?sslmode=require&ssl=true&applicationName=app");
    }

    @Test
    void uriFormProducesSameCanonicalUrlAsJdbcForm() {
        ParsedConnection uri = parser.parse(
                "postgres://user:p%40ss@h:5432/db?sslmode=require");
        ParsedConnection jdbc = parser.parse(
                "jdbc:postgresql://h:5432/db?sslmode=require");
        assertThat(uri.jdbcUrl()).isEqualTo(jdbc.jdbcUrl());
        assertThat(uri.jdbcUrl())
                .isEqualTo("jdbc:postgresql://h:5432/db?sslmode=require");
        assertThat(uri.username()).isEqualTo("user");
        assertThat(uri.password()).isEqualTo("p@ss");
    }

    @Test
    void uriFormAcceptsCredentialParameters() {
        ParsedConnection p = parser.parse(
                "postgres://h/db?user=u&password=p&ssl=true");
        assertThat(p.username()).isEqualTo("u");
        assertThat(p.password()).isEqualTo("p");
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:postgresql://h/db?ssl=true");
    }

    @Test
    void sqlServerUriFormRebuildsCanonicalUrl() {
        ParsedConnection p = parser.parse(
                "sqlserver://admin@sql-host:1433/reportdb?encrypt=true&trustServerCertificate=true");
        assertThat(p.jdbcUrl()).isEqualTo(
                "jdbc:sqlserver://sql-host:1433;databaseName=reportdb;encrypt=true;trustServerCertificate=true");
    }

    @Test
    void credentialParametersAreAcceptedByPolicy() {
        ParsedConnection p = parser.parse("jdbc:postgresql://h/app?user=u&password=p");
        assertThatCode(() -> new ConnectionPolicy(List.of(), false).validate(p))
                .doesNotThrowAnyException();
    }

    @Test
    void namedSqlServerInstanceIsPreservedAsParameter() {
        ParsedConnection p = parser.parse(
                "jdbc:sqlserver://sql-host\\SQLEXPRESS:1433;databaseName=reportdb");
        assertThat(p.parameters()).containsEntry("instanceName", "SQLEXPRESS");
        assertThat(p.jdbcUrl())
                .isEqualTo("jdbc:sqlserver://sql-host:1433;instanceName=SQLEXPRESS;databaseName=reportdb");
    }
}
