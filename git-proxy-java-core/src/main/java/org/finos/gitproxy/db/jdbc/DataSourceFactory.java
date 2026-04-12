package org.finos.gitproxy.db.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/** Convenience factory for creating HikariCP-backed DataSources for each supported database. */
public final class DataSourceFactory {

    private DataSourceFactory() {}

    /** H2 in-memory database with default pool settings. Data is lost on JVM shutdown. */
    public static DataSource h2InMemory(String dbName) {
        return h2InMemory(dbName, new PoolConfig());
    }

    /** H2 in-memory database. Data is lost on JVM shutdown. */
    public static DataSource h2InMemory(String dbName, PoolConfig pool) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        applyPool(config, pool);
        return new HikariDataSource(config);
    }

    /** H2 file-based database with default pool settings. Data persists across restarts. */
    public static DataSource h2File(String filePath) {
        return h2File(filePath, new PoolConfig());
    }

    /** H2 file-based database. Data persists across restarts. */
    public static DataSource h2File(String filePath, PoolConfig pool) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + filePath);
        config.setDriverClassName("org.h2.Driver");
        applyPool(config, pool);
        return new HikariDataSource(config);
    }

    /** PostgreSQL database with default pool settings. */
    public static DataSource postgres(String host, int port, String database, String username, String password) {
        return postgres(host, port, database, username, password, new PoolConfig());
    }

    /** PostgreSQL database. */
    public static DataSource postgres(
            String host, int port, String database, String username, String password, PoolConfig pool) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        applyPool(config, pool);
        return new HikariDataSource(config);
    }

    /** Generic datasource from a JDBC URL, with default pool settings. */
    public static DataSource fromUrl(String jdbcUrl, String username, String password) {
        return fromUrl(jdbcUrl, username, password, new PoolConfig());
    }

    /** Generic datasource from a JDBC URL. */
    public static DataSource fromUrl(String jdbcUrl, String username, String password, PoolConfig pool) {
        HikariConfig config = new HikariConfig();
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            // Use PGSimpleDataSource so PostgreSQL's own PGProperty.readURL() handles all
            // connection properties (including SSL params like sslfactory/sslmode) correctly.
            // HikariCP's setJdbcUrl path silently drops these query parameters.
            config.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
            config.addDataSourceProperty("url", jdbcUrl);
        } else {
            config.setJdbcUrl(jdbcUrl);
        }
        if (username != null) config.setUsername(username);
        if (password != null) config.setPassword(password);
        applyPool(config, pool);
        return new HikariDataSource(config);
    }

    private static void applyPool(HikariConfig config, PoolConfig pool) {
        config.setMaximumPoolSize(pool.getMaximumPoolSize());
        if (pool.getMinimumIdle() >= 0) {
            config.setMinimumIdle(pool.getMinimumIdle());
        }
        config.setConnectionTimeout(pool.getConnectionTimeout());
        config.setIdleTimeout(pool.getIdleTimeout());
        config.setMaxLifetime(pool.getMaxLifetime());
    }
}
