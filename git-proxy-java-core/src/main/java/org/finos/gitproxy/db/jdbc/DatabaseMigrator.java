package org.finos.gitproxy.db.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal SQL migration runner — no external dependencies.
 *
 * <p>Migrations are applied in version order and tracked in a {@code schema_migrations} table. Vendor-specific
 * migrations (e.g. PostgreSQL-only column widening) are included only when the connected database matches. On first run
 * against an existing database that was previously managed by Flyway, the {@code flyway_schema_history} table is read
 * to seed {@code schema_migrations} so already-applied scripts are not re-run.
 */
public class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    // ---------------------------------------------------------------------------
    // Migration registry — add new entries here when adding migration files.
    // Vendor-specific migrations are applied only when isPostgres is true.
    // ---------------------------------------------------------------------------

    private record Migration(String version, String description, String resource, boolean postgresOnly) {}

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration("1", "initial schema", "db/migration/V1__initial_schema.sql", false),
            new Migration("2", "provider id format", "db/migration/V2__provider_id_format.sql", false),
            new Migration(
                    "2.1", "widen provider columns", "db/migration-postgresql/V2_1__widen_provider_columns.sql", true),
            new Migration("3", "email unique constraint", "db/migration/V3__email_unique.sql", false),
            new Migration("4", "spring session tables", "db/migration/V4__spring_session.sql", false));

    // ---------------------------------------------------------------------------

    private DatabaseMigrator() {}

    public static void migrate(DataSource dataSource) {
        log.info("Running database migrations");
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            boolean isPostgres = isPostgres(conn);
            ensureMigrationsTable(conn);
            bootstrapFromFlyway(conn);
            applyPending(conn, isPostgres);
            conn.commit();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Database migration failed", e);
        }
        log.info("Database migrations complete");
    }

    // ---------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------

    private static void ensureMigrationsTable(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version     VARCHAR(20)  NOT NULL PRIMARY KEY,
                        description VARCHAR(255) NOT NULL,
                        applied_at  TIMESTAMP    NOT NULL
                    )
                    """);
        }
    }

    /**
     * If {@code flyway_schema_history} exists and {@code schema_migrations} is empty, copy the successfully applied
     * versions across so we don't re-run migrations on existing databases.
     */
    private static void bootstrapFromFlyway(Connection conn) throws SQLException {
        if (!tableExists(conn, "flyway_schema_history")) return;

        try (var check = conn.prepareStatement("SELECT COUNT(*) FROM schema_migrations")) {
            var rs = check.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) return; // already bootstrapped
        }

        log.info("Seeding schema_migrations from flyway_schema_history");
        try (var flyway = conn.prepareStatement(
                "SELECT version, description, installed_on FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank")) {
            var rs = flyway.executeQuery();
            try (var insert = conn.prepareStatement(
                    "INSERT INTO schema_migrations (version, description, applied_at) VALUES (?, ?, ?)")) {
                while (rs.next()) {
                    String version = rs.getString("version");
                    String desc = rs.getString("description");
                    Timestamp appliedAt = rs.getTimestamp("installed_on");
                    insert.setString(1, version);
                    insert.setString(2, desc != null ? desc : "");
                    insert.setTimestamp(3, appliedAt);
                    insert.executeUpdate();
                    log.info("  Imported Flyway migration v{}: {}", version, desc);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Apply
    // ---------------------------------------------------------------------------

    private static void applyPending(Connection conn, boolean isPostgres) throws SQLException, IOException {
        List<String> applied = appliedVersions(conn);

        List<Migration> pending = new ArrayList<>();
        for (Migration m : MIGRATIONS) {
            if (m.postgresOnly() && !isPostgres) continue;
            if (!applied.contains(m.version())) pending.add(m);
        }
        pending.sort((a, b) -> compareVersions(a.version(), b.version()));

        if (pending.isEmpty()) {
            log.info("Schema up to date (applied: {})", applied.isEmpty() ? "none" : String.join(", ", applied));
            return;
        }

        for (Migration m : pending) {
            log.info("Applying migration v{}: {}", m.version(), m.description());
            String sql = loadResource(m.resource());
            for (String statement : splitStatements(sql)) {
                try (var st = conn.createStatement()) {
                    st.execute(statement);
                }
            }
            try (var insert = conn.prepareStatement(
                    "INSERT INTO schema_migrations (version, description, applied_at) VALUES (?, ?, ?)")) {
                insert.setString(1, m.version());
                insert.setString(2, m.description());
                insert.setTimestamp(3, Timestamp.from(Instant.now()));
                insert.executeUpdate();
            }
            log.info("  Applied v{}", m.version());
        }
    }

    private static List<String> appliedVersions(Connection conn) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (var st = conn.createStatement();
                var rs = st.executeQuery("SELECT version FROM schema_migrations")) {
            while (rs.next()) versions.add(rs.getString(1));
        }
        return versions;
    }

    // ---------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------

    private static boolean isPostgres(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        return product.contains("postgresql");
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        var meta = conn.getMetaData();
        // H2 stores names in upper case by default; PostgreSQL in lower case
        try (var rs = meta.getTables(null, null, tableName, new String[] {"TABLE"})) {
            if (rs.next()) return true;
        }
        try (var rs = meta.getTables(null, null, tableName.toUpperCase(Locale.ROOT), new String[] {"TABLE"})) {
            return rs.next();
        }
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = DatabaseMigrator.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Migration resource not found on classpath: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits a SQL script on {@code ;} boundaries, discarding comments and blank statements. Good enough for plain
     * DDL/DML scripts; not a full SQL parser.
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        // Strip single-line comments before splitting
        String stripped = sql.replaceAll("--[^\n]*", "");
        for (String part : stripped.split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) statements.add(trimmed);
        }
        return statements;
    }

    /** Compares dot-separated version strings numerically, e.g. {@code "2.1" > "2"}. */
    static int compareVersions(String a, String b) {
        int[] aParts = Arrays.stream(a.split("\\.")).mapToInt(Integer::parseInt).toArray();
        int[] bParts = Arrays.stream(b.split("\\.")).mapToInt(Integer::parseInt).toArray();
        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            int av = i < aParts.length ? aParts[i] : 0;
            int bv = i < bParts.length ? bParts[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }
}
