package org.finos.gitproxy.db.jdbc;

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
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();
        log.info("Flyway migrations complete");
    }
}
