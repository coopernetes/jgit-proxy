package org.finos.gitproxy.db.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/** Convenience factory for creating HikariCP-backed DataSources for each supported database. */
public final class DataSourceFactory {

    private DataSourceFactory() {}

    /** H2 in-memory database. Data is lost on JVM shutdown. */
    public static DataSource h2InMemory(String dbName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    /** H2 file-based database. Data persists across restarts. */
    public static DataSource h2File(String filePath) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + filePath);
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    /** PostgreSQL database. */
    public static DataSource postgres(String host, int port, String database, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }

    /** Generic datasource from a JDBC URL. */
    public static DataSource fromUrl(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null) config.setUsername(username);
        if (password != null) config.setPassword(password);
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }
}
