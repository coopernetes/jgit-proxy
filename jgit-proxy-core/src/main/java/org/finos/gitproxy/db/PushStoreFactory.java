package org.finos.gitproxy.db;

import com.mongodb.client.MongoClients;
import javax.sql.DataSource;
import org.finos.gitproxy.db.jdbc.DataSourceFactory;
import org.finos.gitproxy.db.jdbc.JdbcPushStore;
import org.finos.gitproxy.db.memory.InMemoryPushStore;
import org.finos.gitproxy.db.mongo.MongoPushStore;

/**
 * Factory for creating {@link PushStore} instances from simple configuration parameters. Handles initialization
 * automatically.
 */
public final class PushStoreFactory {

    private PushStoreFactory() {}

    /** Create an in-memory store (data is lost on restart). */
    public static PushStore inMemory() {
        InMemoryPushStore store = new InMemoryPushStore();
        store.initialize();
        return store;
    }

    /** Create an H2 in-memory store (SQL with schema, but data is lost on restart). */
    public static PushStore h2InMemory() {
        return h2InMemory("gitproxy");
    }

    /** Create an H2 in-memory store with a custom database name. */
    public static PushStore h2InMemory(String dbName) {
        JdbcPushStore store = new JdbcPushStore(DataSourceFactory.h2InMemory(dbName));
        store.initialize();
        return store;
    }

    /** Create an H2 file-based store. */
    public static PushStore h2File(String filePath) {
        JdbcPushStore store = new JdbcPushStore(DataSourceFactory.h2File(filePath));
        store.initialize();
        return store;
    }

    /** Create a SQLite file-based store. */
    public static PushStore sqlite(String filePath) {
        JdbcPushStore store = new JdbcPushStore(DataSourceFactory.sqlite(filePath));
        store.initialize();
        return store;
    }

    /** Create a PostgreSQL store. */
    public static PushStore postgres(String host, int port, String database, String username, String password) {
        JdbcPushStore store = new JdbcPushStore(DataSourceFactory.postgres(host, port, database, username, password));
        store.initialize();
        return store;
    }

    /** Create a MongoDB store. */
    public static PushStore mongo(String connectionString, String databaseName) {
        MongoPushStore store = new MongoPushStore(MongoClients.create(connectionString), databaseName);
        store.initialize();
        return store;
    }

    /** Create a store from a JDBC URL (auto-detects H2, SQLite, or Postgres). */
    public static PushStore fromJdbcUrl(String jdbcUrl, String username, String password) {
        JdbcPushStore store = new JdbcPushStore(DataSourceFactory.fromUrl(jdbcUrl, username, password));
        store.initialize();
        return store;
    }

    /** Create a store from an already-configured {@link DataSource} (shared pool use case). */
    public static PushStore fromDataSource(DataSource dataSource) {
        JdbcPushStore store = new JdbcPushStore(dataSource);
        store.initialize();
        return store;
    }
}
