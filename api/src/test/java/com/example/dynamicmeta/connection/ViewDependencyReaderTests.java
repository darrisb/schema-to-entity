package com.example.dynamicmeta.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ViewDependencyReaderTests {

    private final ViewDependencyReader reader = new ViewDependencyReader();

    @Mock private Connection connection;
    @Mock private PreparedStatement statement;
    @Mock private ResultSet resultSet;

    @Test
    void readsPostgresCatalogDependenciesIntoBaseTables() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getString("view_name"))
                .thenReturn("customer_names", "customer_names", "order_totals");
        when(resultSet.getString("table_name")).thenReturn("customers", "customers", "orders");

        Map<String, List<String>> result =
                reader.read(DatabaseType.POSTGRESQL, connection, "mydb", "public");

        assertThat(result)
                .containsEntry("customer_names", List.of("customers"))
                .containsEntry("order_totals", List.of("orders"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        // information_schema.view_table_usage hides tables the current role does not own,
        // so Postgres must read pg_depend to work for read-only accounts.
        assertThat(sql.getValue())
                .contains("pg_catalog.pg_depend")
                .doesNotContain("information_schema.view_table_usage")
                .doesNotContain("mydb")
                .doesNotContain("public");
        verify(statement).setString(1, "public");
        verify(statement, never()).setString(eq(2), anyString());
    }

    @Test
    void sqlServerFiltersViewTableUsageByCatalogAndSchema() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("view_name")).thenReturn("customer_names");
        when(resultSet.getString("table_name")).thenReturn("customers");

        Map<String, List<String>> result =
                reader.read(DatabaseType.SQLSERVER, connection, "mydb", "dbo");

        assertThat(result).containsEntry("customer_names", List.of("customers"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("information_schema.view_table_usage");
        verify(statement).setString(1, "mydb");
        verify(statement).setString(2, "dbo");
    }

    @Test
    void mysqlFiltersViewTableUsageByTheCatalogSlot() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("view_name")).thenReturn("customer_names");
        when(resultSet.getString("table_name")).thenReturn("customers");

        Map<String, List<String>> result =
                reader.read(DatabaseType.MYSQL, connection, "mydb", null);

        assertThat(result).containsEntry("customer_names", List.of("customers"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("information_schema.view_table_usage");
        verify(statement).setString(1, "mydb");
    }

    @Test
    void oracleReadsAllDependenciesByOwner() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("name")).thenReturn("CUSTOMER_NAMES");
        when(resultSet.getString("referenced_name")).thenReturn("CUSTOMERS");

        Map<String, List<String>> result =
                reader.read(DatabaseType.ORACLE, connection, null, "APP_USER");

        assertThat(result).containsEntry("CUSTOMER_NAMES", List.of("CUSTOMERS"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("ALL_DEPENDENCIES")
                .contains("TYPE = 'VIEW'")
                .contains("REFERENCED_TYPE = 'TABLE'")
                .contains("referenced_owner = owner")
                .doesNotContain("APP_USER");
        verify(statement).setString(1, "APP_USER");
    }

    @Test
    void returnsEmptyMapWhenTheCatalogQueryFails() throws Exception {
        when(connection.prepareStatement(anyString()))
                .thenThrow(new SQLException("permission denied"));

        assertThat(reader.read(DatabaseType.POSTGRESQL, connection, "mydb", "public")).isEmpty();
    }

    @Test
    void returnsEmptyMapForADatabaseWithoutAViewCatalog() throws Exception {
        assertThat(reader.read(DatabaseType.H2, connection, "db", "PUBLIC")).isEmpty();

        verifyNoInteractions(connection);
    }
}
