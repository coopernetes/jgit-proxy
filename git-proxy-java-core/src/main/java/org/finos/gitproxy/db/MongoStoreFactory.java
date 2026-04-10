package org.finos.gitproxy.db;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.finos.gitproxy.db.mongo.MongoFetchStore;
import org.finos.gitproxy.db.mongo.MongoPushStore;
import org.finos.gitproxy.db.mongo.MongoRepoRegistry;
import org.finos.gitproxy.permission.MongoRepoPermissionStore;
import org.finos.gitproxy.permission.RepoPermissionStore;
import org.finos.gitproxy.user.MongoUserStore;
import org.finos.gitproxy.user.UserStore;

/**
 * Factory that creates MongoDB-backed stores sharing a single {@link MongoClient}. Callers that need both a
 * {@link PushStore} and a {@link FetchStore} should create one instance of this class so the underlying connection pool
 * is shared.
 */
public final class MongoStoreFactory implements AutoCloseable {

    private final MongoClient client;
    private final String databaseName;

    public MongoStoreFactory(String connectionString, String databaseName) {
        this.client = MongoClients.create(connectionString);
        this.databaseName = databaseName;
    }

    /** Create and initialize a {@link PushStore} backed by this factory's client. */
    public PushStore pushStore() {
        MongoPushStore store = new MongoPushStore(client, databaseName);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link FetchStore} backed by this factory's client. */
    public FetchStore fetchStore() {
        MongoFetchStore store = new MongoFetchStore(client, databaseName);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link RepoRegistry} backed by this factory's client. */
    public RepoRegistry repoRegistry() {
        MongoRepoRegistry store = new MongoRepoRegistry(client, databaseName);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link RepoPermissionStore} backed by this factory's client. */
    public RepoPermissionStore repoPermissionStore() {
        MongoRepoPermissionStore store = new MongoRepoPermissionStore(client, databaseName);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link UserStore} backed by this factory's client. */
    public UserStore userStore() {
        MongoUserStore store = new MongoUserStore(client, databaseName);
        store.initialize();
        return store;
    }

    @Override
    public void close() {
        client.close();
    }
}
