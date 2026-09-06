package com.rbc.fogwall.db.jdbc;

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
 * migrations (e.g. PostgreSQL-only column widening, or MySQL/MariaDB rewrites of syntax Postgres/H2 accept but MySQL
 * does not — {@code BYTEA}, {@code ALTER COLUMN ... SET NOT NULL}, {@code CREATE INDEX IF NOT EXISTS}) are included
 * only when the connected database matches. Some shared migrations are consequently marked {@link Vendor#EXCEPT_MYSQL}
 * — a MySQL/MariaDB-specific replacement of the same version exists under {@code db/migration-mysql/} and is selected
 * instead via {@link Vendor#MYSQL_ONLY}. On first run against an existing database that was previously managed by
 * Flyway, the {@code flyway_schema_history} table is read to seed {@code schema_migrations} so already-applied scripts
 * are not re-run.
 */
public class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    // ---------------------------------------------------------------------------
    // Migration registry — add new entries here when adding migration files.
    // ---------------------------------------------------------------------------

    /** Which database family a migration applies to. */
    private enum Vendor {
        /** Applies to every supported database. */
        ANY,
        /** PostgreSQL only. */
        POSTGRES_ONLY,
        /** MySQL or MariaDB only. */
        MYSQL_ONLY,
        /** Every database except MySQL/MariaDB — a MySQL-specific replacement of this version exists. */
        EXCEPT_MYSQL
    }

    private record Migration(String version, String description, String resource, Vendor vendor) {}

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration("1", "initial schema", "db/migration/V1__initial_schema.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "1",
                    "initial schema (mysql/mariadb)",
                    "db/migration-mysql/V1__initial_schema.sql",
                    Vendor.MYSQL_ONLY),
            new Migration("2", "provider id format", "db/migration/V2__provider_id_format.sql", Vendor.ANY),
            new Migration(
                    "2.1",
                    "widen provider columns",
                    "db/migration-postgresql/V2_1__widen_provider_columns.sql",
                    Vendor.POSTGRES_ONLY),
            new Migration(
                    "2.1",
                    "widen provider columns (mysql/mariadb)",
                    "db/migration-mysql/V2_1__widen_provider_columns.sql",
                    Vendor.MYSQL_ONLY),
            new Migration("3", "email unique constraint", "db/migration/V3__email_unique.sql", Vendor.ANY),
            new Migration("4", "spring session tables", "db/migration/V4__spring_session.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "4",
                    "spring session tables (mysql/mariadb)",
                    "db/migration-mysql/V4__spring_session.sql",
                    Vendor.MYSQL_ONLY),
            new Migration("5", "unified rule shape", "db/migration/V5__unified_rule_shape.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "5",
                    "unified rule shape (mysql/mariadb)",
                    "db/migration-mysql/V5__unified_rule_shape.sql",
                    Vendor.MYSQL_ONLY),
            new Migration("6", "repo permissions FK", "db/migration/V6__repo_permissions_fk.sql", Vendor.ANY),
            new Migration(
                    "7",
                    "rename operations to operation",
                    "db/migration/V7__rename_operations_to_operation.sql",
                    Vendor.ANY),
            new Migration("8", "user ssh keys", "db/migration/V8__ssh_keys.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "8", "user ssh keys (mysql/mariadb)", "db/migration-mysql/V8__ssh_keys.sql", Vendor.MYSQL_ONLY),
            new Migration("9", "permission groups", "db/migration/V9__permission_groups.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "9",
                    "permission groups (mysql/mariadb)",
                    "db/migration-mysql/V9__permission_groups.sql",
                    Vendor.MYSQL_ONLY),
            new Migration("10", "scm oauth tokens", "db/migration/V10__scm_oauth_tokens.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "10",
                    "scm oauth tokens (mysql/mariadb)",
                    "db/migration-mysql/V10__scm_oauth_tokens.sql",
                    Vendor.MYSQL_ONLY),
            new Migration(
                    "11",
                    "ssh key locked flag and auth source",
                    "db/migration/V11__ssh_key_locked.sql",
                    Vendor.EXCEPT_MYSQL),
            new Migration(
                    "11",
                    "ssh key locked flag and auth source (mysql/mariadb)",
                    "db/migration-mysql/V11__ssh_key_locked.sql",
                    Vendor.MYSQL_ONLY),
            new Migration("12", "ssh key sources", "db/migration/V12__ssh_key_sources.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "12",
                    "ssh key sources (mysql/mariadb)",
                    "db/migration-mysql/V12__ssh_key_sources.sql",
                    Vendor.MYSQL_ONLY),
            new Migration("13", "email sources", "db/migration/V13__email_sources.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "13",
                    "email sources (mysql/mariadb)",
                    "db/migration-mysql/V13__email_sources.sql",
                    Vendor.MYSQL_ONLY),
            new Migration(
                    "14",
                    "push commit co-authored-by trailers",
                    "db/migration/V14__push_commit_co_authored_by.sql",
                    Vendor.EXCEPT_MYSQL),
            new Migration(
                    "14",
                    "push commit co-authored-by trailers (mysql/mariadb)",
                    "db/migration-mysql/V14__push_commit_co_authored_by.sql",
                    Vendor.MYSQL_ONLY),
            new Migration("15", "scm api proxy", "db/migration/V15__scm_api_proxy.sql", Vendor.EXCEPT_MYSQL),
            new Migration(
                    "15",
                    "scm api proxy (mysql/mariadb)",
                    "db/migration-mysql/V15__scm_api_proxy.sql",
                    Vendor.MYSQL_ONLY),
            // A plain ADD COLUMN, identical on every supported engine, so there is no MySQL-specific variant.
            new Migration(
                    "16", "scm token cache scm login", "db/migration/V16__scm_token_cache_login.sql", Vendor.ANY));

    // ---------------------------------------------------------------------------

    private DatabaseMigrator() {}

    public static void migrate(DataSource dataSource) {
        log.info("Running database migrations");
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            boolean isPostgres = isPostgres(conn);
            boolean isMysql = isMysqlFamily(conn);
            ensureMigrationsTable(conn);
            bootstrapFromFlyway(conn);
            applyPending(conn, isPostgres, isMysql);
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

    private static void applyPending(Connection conn, boolean isPostgres, boolean isMysql)
            throws SQLException, IOException {
        List<String> applied = appliedVersions(conn);

        List<Migration> pending = new ArrayList<>();
        for (Migration m : MIGRATIONS) {
            if (!applies(m.vendor(), isPostgres, isMysql)) continue;
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

    private static boolean applies(Vendor vendor, boolean isPostgres, boolean isMysql) {
        return switch (vendor) {
            case ANY -> true;
            case POSTGRES_ONLY -> isPostgres;
            case MYSQL_ONLY -> isMysql;
            case EXCEPT_MYSQL -> !isMysql;
        };
    }

    private static boolean isPostgres(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        return product.contains("postgresql");
    }

    /** MySQL and MariaDB are treated as one family for migration purposes — same schema, same DDL dialect. */
    private static boolean isMysqlFamily(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        return product.contains("mysql") || product.contains("mariadb");
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
