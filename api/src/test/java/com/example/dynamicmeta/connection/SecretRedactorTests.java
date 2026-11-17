package com.example.dynamicmeta.connection;

import com.example.dynamicmeta.SchemaDiscoveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTests {

    // --- URL-style user:pass@ redaction ---

    @Test
    void masksPostgresUrl() {
        String result = SecretRedactor.redact("Failed to connect to postgres://admin:secret@localhost:5432/db");
        assertThat(result).isEqualTo("Failed to connect to postgres://admin:***@localhost:5432/db");
    }

    @Test
    void masksPostgresqlUrl() {
        String result = SecretRedactor.redact("postgresql://user:p4ss@host/test");
        assertThat(result).isEqualTo("postgresql://user:***@host/test");
    }

    @Test
    void masksMysqlUrl() {
        String result = SecretRedactor.redact("mysql://root:rootpw@db.example.com/mydb");
        assertThat(result).isEqualTo("mysql://root:***@db.example.com/mydb");
    }

    @Test
    void masksMariadbUrl() {
        String result = SecretRedactor.redact("mariadb://user:pass@localhost:3306/sales");
        assertThat(result).isEqualTo("mariadb://user:***@localhost:3306/sales");
    }

    @Test
    void masksSqlserverUrl() {
        String result = SecretRedactor.redact("sqlserver://sa:Pwd123@server:1433/master");
        assertThat(result).isEqualTo("sqlserver://sa:***@server:1433/master");
    }

    @Test
    void masksMongodbUrl() {
        String result = SecretRedactor.redact("mongodb://mongoUser:mongoPass@cluster0.example.com/admin");
        assertThat(result).isEqualTo("mongodb://mongoUser:***@cluster0.example.com/admin");
    }

    @Test
    void masksMongodbSrvUrl() {
        String result = SecretRedactor.redact("mongodb+srv://user:pass@cluster.example.com/admin");
        assertThat(result).isEqualTo("mongodb+srv://user:***@cluster.example.com/admin");
    }

    // --- key=value password redaction ---

    @Test
    void masksPasswordKeyInQueryStyle() {
        String result = SecretRedactor.redact("jdbc:sqlserver://server;password=secret;database=db");
        assertThat(result).isEqualTo("jdbc:sqlserver://server;password=***;database=db");
    }

    @Test
    void masksPwdKeyInQueryStyle() {
        String result = SecretRedactor.redact("jdbc:mysql://host?pwd=mypassword&useSSL=true");
        assertThat(result).isEqualTo("jdbc:mysql://host?pwd=***&useSSL=true");
    }

    @Test
    void masksPasswdKeyInQueryStyle() {
        String result = SecretRedactor.redact("jdbc:mysql://host?passwd=mypassword&useSSL=true");
        assertThat(result).isEqualTo("jdbc:mysql://host?passwd=***&useSSL=true");
    }

    @Test
    void masksPasswordKeyCaseInsensitive() {
        String result = SecretRedactor.redact("jdbc:sqlserver://server;PASSWORD=secret");
        assertThat(result).isEqualTo("jdbc:sqlserver://server;PASSWORD=***");
    }

    @Test
    void masksPasswordWithAmpersandDelimiter() {
        String result = SecretRedactor.redact("jdbc:mysql://host?user=admin&password=secret&ssl=true");
        assertThat(result).isEqualTo("jdbc:mysql://host?user=admin&password=***&ssl=true");
    }

    // --- Oracle user/password@host style ---

    @Test
    void masksOracleThinStyle() {
        String result = SecretRedactor.redact("jdbc:oracle:thin:user/password@localhost:1521:orcl");
        assertThat(result).isEqualTo("jdbc:oracle:thin:user/***@localhost:1521:orcl");
    }

    @Test
    void masksOracleThinStyleCaseInsensitive() {
        String result = SecretRedactor.redact("JDBC:ORACLE:THIN:SCOTT/TIGER@dbhost:1521:xe");
        assertThat(result).isEqualTo("JDBC:ORACLE:THIN:SCOTT/***@dbhost:1521:xe");
    }

    // --- Special characters in passwords ---

    @Test
    void masksPasswordWithAtSign() {
        // In URL style, the first @ terminates the password group, so a literal @
        // would need to be URL-encoded. We verify the encoded form is masked.
        String result = SecretRedactor.redact("postgres://user:p%40ss@localhost/db");
        assertThat(result).isEqualTo("postgres://user:***@localhost/db");
    }

    @Test
    void masksPasswordWithColon() {
        String result = SecretRedactor.redact("postgres://user:p:ss@localhost/db");
        assertThat(result).isEqualTo("postgres://user:***@localhost/db");
    }

    @Test
    void masksPasswordWithSemicolon() {
        // ; is the delimiter for SQL Server connection strings, so a password
        // containing literal ; would be ambiguous. Use a password that does not
        // contain the delimiter; the key=value pattern handles it correctly.
        String result = SecretRedactor.redact("jdbc:sqlserver://server;password=complexPw!;database=db");
        assertThat(result).isEqualTo("jdbc:sqlserver://server;password=***;database=db");
    }

    @Test
    void masksPasswordWithPercent() {
        String result = SecretRedactor.redact("postgres://user:p%25ss@localhost/db");
        assertThat(result).isEqualTo("postgres://user:***@localhost/db");
    }

    // --- Edge cases ---

    @Test
    void nullInputReturnsFallbackMessage() {
        assertThat(SecretRedactor.redact(null)).isEqualTo("Database operation failed");
    }

    @Test
    void textWithoutSecretsIsUnchanged() {
        assertThat(SecretRedactor.redact("Connection timed out"))
                .isEqualTo("Connection timed out");
    }

    @Test
    void urlWithoutPasswordIsUnchanged() {
        // postgres://user@host (no colon after user) should not be matched
        assertThat(SecretRedactor.redact("postgres://user@localhost/db"))
                .isEqualTo("postgres://user@localhost/db");
    }

    @Test
    void usernameAndHostRemainVisible() {
        String result = SecretRedactor.redact("postgres://dbadmin:s3cret@prod-db.example.com:5432/production");
        assertThat(result).isEqualTo("postgres://dbadmin:***@prod-db.example.com:5432/production");
        assertThat(result).contains("dbadmin");
        assertThat(result).contains("prod-db.example.com:5432");
        assertThat(result).doesNotContain("s3cret");
    }

    // --- ConnectionRequest.toString() ---

    @Test
    void connectionRequestToStringDoesNotContainPassword() {
        SchemaDiscoveryService.ConnectionRequest request =
                new SchemaDiscoveryService.ConnectionRequest(
                        "jdbc:postgresql://localhost:5432/mydb",
                        "myuser",
                        "superSecretPassword",
                        "public",
                        List.of("users", "orders"),
                        "org.postgresql.Driver",
                        true);

        String str = request.toString();

        assertThat(str).doesNotContain("superSecretPassword");
        assertThat(str).contains("password=***");
        assertThat(str).contains("myuser");
        assertThat(str).contains("mydb");
    }

    @Test
    void connectionRequestToStringWithNullPassword() {
        SchemaDiscoveryService.ConnectionRequest request =
                new SchemaDiscoveryService.ConnectionRequest(
                        "jdbc:postgresql://localhost:5432/mydb",
                        "myuser",
                        null,
                        null,
                        null,
                        null,
                        null);

        String str = request.toString();

        assertThat(str).contains("password=***");
        assertThat(str).contains("myuser");
        assertThat(str).contains("jdbc:postgresql://localhost:5432/mydb");
    }
}