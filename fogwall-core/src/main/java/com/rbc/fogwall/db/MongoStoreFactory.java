package com.rbc.fogwall.db;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rbc.fogwall.db.mongo.MongoFetchStore;
import com.rbc.fogwall.db.mongo.MongoGitHubNodeIdCache;
import com.rbc.fogwall.db.mongo.MongoGitLabProjectIdCache;
import com.rbc.fogwall.db.mongo.MongoPushStore;
import com.rbc.fogwall.db.mongo.MongoScmApiActionStore;
import com.rbc.fogwall.db.mongo.MongoScmTokenCache;
import com.rbc.fogwall.db.mongo.MongoSshFingerprintCache;
import com.rbc.fogwall.db.mongo.MongoUrlRuleRegistry;
import com.rbc.fogwall.permission.GroupPermissionStore;
import com.rbc.fogwall.permission.MongoGroupPermissionStore;
import com.rbc.fogwall.permission.MongoRepoPermissionStore;
import com.rbc.fogwall.permission.PermissionStore;
import com.rbc.fogwall.permission.RepoPermission;
import com.rbc.fogwall.scmapi.GitHubNodeIdCache;
import com.rbc.fogwall.scmapi.GitLabProjectIdCache;
import com.rbc.fogwall.service.ScmTokenCache;
import com.rbc.fogwall.service.SshFingerprintCache;
import com.rbc.fogwall.user.MongoUserStore;
import com.rbc.fogwall.user.UserStore;
import java.time.Duration;

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

    /** Returns the shared {@link MongoClient} so callers (e.g. session store) can reuse the connection pool. */
    public MongoClient getMongoClient() {
        return client;
    }

    /** Returns the configured database name. */
    public String getDatabaseName() {
        return databaseName;
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

    /** Create and initialize a {@link UrlRuleRegistry} backed by this factory's client. */
    public UrlRuleRegistry repoRegistry() {
        MongoUrlRuleRegistry store = new MongoUrlRuleRegistry(client, databaseName);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link PermissionStore} backed by this factory's client. */
    public PermissionStore<RepoPermission> repoPermissionStore() {
        MongoRepoPermissionStore store = new MongoRepoPermissionStore(client, databaseName);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link GroupPermissionStore} backed by this factory's client. */
    public GroupPermissionStore groupPermissionStore() {
        MongoGroupPermissionStore store = new MongoGroupPermissionStore(client, databaseName);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link UserStore} backed by this factory's client. */
    public UserStore userStore() {
        MongoUserStore store = new MongoUserStore(client, databaseName);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link UserStore} backed by this factory's client, with cache eviction wired in. */
    public UserStore userStore(ScmTokenCache tokenCache) {
        MongoUserStore store = new MongoUserStore(client, databaseName, tokenCache);
        store.initialize();
        return store;
    }

    /** Create and initialize a {@link SshFingerprintCache} backed by this factory's client. */
    public SshFingerprintCache sshFingerprintCache(Duration ttl) {
        MongoSshFingerprintCache cache = new MongoSshFingerprintCache(client, databaseName, ttl);
        cache.initialize();
        return cache;
    }

    /** Create and initialize a {@link ScmTokenCache} backed by this factory's client. */
    public ScmTokenCache tokenCache(Duration maxAge) {
        MongoScmTokenCache cache = new MongoScmTokenCache(client, databaseName, maxAge);
        cache.initialize();
        return cache;
    }

    /** Create and initialize a {@link GitHubNodeIdCache} for the SCM API proxy, backed by this factory's client. */
    public GitHubNodeIdCache nodeIdCache(Duration ttl) {
        MongoGitHubNodeIdCache cache = new MongoGitHubNodeIdCache(client, databaseName, ttl);
        cache.initialize();
        return cache;
    }

    /** Create and initialize a {@link GitLabProjectIdCache} for the SCM API proxy, backed by this factory's client. */
    public GitLabProjectIdCache gitLabProjectIdCache(Duration ttl) {
        MongoGitLabProjectIdCache cache = new MongoGitLabProjectIdCache(client, databaseName, ttl);
        cache.initialize();
        return cache;
    }

    /** Create and initialize a {@link ScmApiActionStore} for the SCM API proxy, backed by this factory's client. */
    public ScmApiActionStore scmApiActionStore() {
        MongoScmApiActionStore store = new MongoScmApiActionStore(client, databaseName);
        store.initialize();
        return store;
    }

    @Override
    public void close() {
        client.close();
    }
}
