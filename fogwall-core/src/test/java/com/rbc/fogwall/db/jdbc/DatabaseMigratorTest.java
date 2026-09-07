package com.rbc.fogwall.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the H2/Postgres migration selection after {@link DatabaseMigrator} was generalized to support
 * MySQL/MariaDB (adding a {@code Vendor} tag alongside the existing postgres-only flag). Adding new
 * {@code MYSQL_ONLY}/{@code EXCEPT_MYSQL} entries must not change which migrations a non-MySQL connection (H2 here,
 * standing in for H2/Postgres) selects or applies.
 */
class DatabaseMigratorTest {

    @Test
    void migrate_onH2_appliesOnlySharedAndExceptMysqlMigrations() throws Exception {
        DataSource dataSource = DataSourceFactory.h2InMemory("migrator-test-" + UUID.randomUUID());
        DatabaseMigrator.migrate(dataSource);

        assertEquals(
                Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16"),
                appliedVersions(dataSource),
                "H2 must never select MYSQL_ONLY entries, and the 2.1 postgres-only widening migration must not "
                        + "apply to H2 either");
    }

    @Test
    void migrate_calledTwice_isIdempotent() throws Exception {
        DataSource dataSource = DataSourceFactory.h2InMemory("migrator-test-" + UUID.randomUUID());
        DatabaseMigrator.migrate(dataSource);
        Set<String> firstRun = appliedVersions(dataSource);

        assertDoesNotThrow(() -> DatabaseMigrator.migrate(dataSource));
        assertEquals(firstRun, appliedVersions(dataSource));
    }

    private static Set<String> appliedVersions(DataSource dataSource) throws Exception {
        Set<String> versions = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT version FROM schema_migrations")) {
            while (rs.next()) versions.add(rs.getString(1));
        }
        return versions;
    }
}
