package com.rbc.fogwall.db.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.rbc.fogwall.service.CachedScmIdentity;
import com.rbc.fogwall.service.ScmTokenCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed cache for SCM token identity resolutions.
 *
 * <p>Stores a mapping of {@code (provider, SHA-512(provider:token)) -> (proxy_username, scm_login)}. A TTL index on
 * {@code cached_at} handles automatic expiry at the MongoDB level; expired entries are also filtered on read so
 * behaviour matches {@link com.rbc.fogwall.service.JdbcScmTokenCache}.
 */
public class MongoScmTokenCache implements ScmTokenCache {

    private static final Logger log = LoggerFactory.getLogger(MongoScmTokenCache.class);
    private static final String COLLECTION_NAME = "scm_token_cache";

    private final MongoDatabase database;
    private final Duration maxAge;

    public MongoScmTokenCache(MongoClient mongoClient, String databaseName, Duration maxAge) {
        this.database = mongoClient.getDatabase(databaseName);
        this.maxAge = maxAge;
    }

    public void initialize() {
        MongoCollection<Document> col = getCollection();
        col.createIndex(Indexes.ascending("provider", "token_hash"), new IndexOptions().unique(true));
        col.createIndex(Indexes.ascending("provider", "proxy_username"));
        // TTL index: MongoDB removes documents automatically after maxAge seconds
        col.createIndex(
                Indexes.ascending("cached_at"),
                new IndexOptions().expireAfter(maxAge.getSeconds(), java.util.concurrent.TimeUnit.SECONDS));
        log.info("MongoDB SCM token cache initialized (max age {} days)", maxAge.toDays());
    }

    @Override
    public Optional<CachedScmIdentity> lookup(String provider, String tokenHash) {
        Date cutoff = Date.from(Instant.now().minus(maxAge));
        Document doc = getCollection()
                .find(Filters.and(
                        Filters.eq("provider", provider),
                        Filters.eq("token_hash", tokenHash),
                        Filters.gte("cached_at", cutoff)))
                .first();
        if (doc == null) return Optional.empty();
        log.debug("SCM token cache hit: provider={}", provider);
        // scm_login is absent on documents written before it was cached; those expire on the TTL index.
        return Optional.of(new CachedScmIdentity(doc.getString("proxy_username"), doc.getString("scm_login")));
    }

    @Override
    public void store(String provider, String tokenHash, CachedScmIdentity identity) {
        Document doc = new Document("provider", provider)
                .append("token_hash", tokenHash)
                .append("proxy_username", identity.proxyUsername())
                .append("scm_login", identity.scmLogin())
                .append("cached_at", Date.from(Instant.now()));
        getCollection()
                .replaceOne(
                        Filters.and(Filters.eq("provider", provider), Filters.eq("token_hash", tokenHash)),
                        doc,
                        new ReplaceOptions().upsert(true));
        log.debug("SCM token cached: provider={}, user={}", provider, identity.proxyUsername());
    }

    @Override
    public void evictByUsername(String provider, String proxyUsername) {
        long deleted = getCollection()
                .deleteMany(Filters.and(Filters.eq("provider", provider), Filters.eq("proxy_username", proxyUsername)))
                .getDeletedCount();
        if (deleted > 0) {
            log.debug("SCM token cache evicted: provider={}, user={}, entries={}", provider, proxyUsername, deleted);
        }
    }

    private MongoCollection<Document> getCollection() {
        return database.getCollection(COLLECTION_NAME);
    }
}
