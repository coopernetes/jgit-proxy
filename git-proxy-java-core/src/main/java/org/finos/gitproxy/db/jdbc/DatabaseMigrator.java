package org.finos.gitproxy.db.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies Flyway database migrations from {@code classpath:db/migration}. */
public class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    private DatabaseMigrator() {}

    public static void migrate(DataSource dataSource) {
        log.info("Running Flyway database migrations");
        List<String> locations = new ArrayList<>();
        locations.add("classpath:db/migration");
        String vendor = detectVendor(dataSource);
        if (vendor != null) {
            locations.add("classpath:db/migration-" + vendor);
            log.info("Adding vendor-specific Flyway location: db/migration-{}", vendor);
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(true)
                .load()
                .migrate();
        log.info("Flyway migrations complete");
    }

    private static String detectVendor(DataSource dataSource) {
        try (var conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("postgresql")) return "postgresql";
            return null;
        } catch (SQLException e) {
            log.warn("Could not detect database vendor for Flyway location selection", e);
            return null;
        }
    }
}
