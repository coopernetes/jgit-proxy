package com.rbc.fogwall.db.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.rbc.fogwall.scmapi.GitHubNodeIdCache;
import com.rbc.fogwall.scmapi.JdbcGitHubNodeIdCache;
import com.rbc.fogwall.scmapi.OwnerRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed cache for SCM API proxy node-ID resolutions.
 *
 * <p>Stores a mapping of {@code (provider, node id) -> owner/repo}. A TTL index on {@code cached_at} handles automatic
 * expiry at the MongoDB level; expired entries are also filtered on read so behaviour matches
 * {@link JdbcGitHubNodeIdCache}.
 */
public class MongoGitHubNodeIdCache implements GitHubNodeIdCache {

    private static final Logger log = LoggerFactory.getLogger(MongoGitHubNodeIdCache.class);
    private static final String COLLECTION_NAME = "scm_api_github_node_cache";

    private final MongoDatabase database;
    private final Duration maxAge;

    public MongoGitHubNodeIdCache(MongoClient mongoClient, String databaseName, Duration maxAge) {
        this.database = mongoClient.getDatabase(databaseName);
        this.maxAge = maxAge;
    }

    public void initialize() {
        MongoCollection<Document> col = getCollection();
        col.createIndex(Indexes.ascending("provider", "node_id"), new IndexOptions().unique(true));
        col.createIndex(
                Indexes.ascending("cached_at"), new IndexOptions().expireAfter(maxAge.getSeconds(), TimeUnit.SECONDS));
        log.info("MongoDB SCM API node-ID cache initialized (max age {})", maxAge);
    }

    @Override
    public Optional<OwnerRepo> lookup(String provider, String nodeId) {
        Date cutoff = Date.from(Instant.now().minus(maxAge));
        Document doc = getCollection()
                .find(Filters.and(
                        Filters.eq("provider", provider),
                        Filters.eq("node_id", nodeId),
                        Filters.gte("cached_at", cutoff)))
                .first();
        if (doc == null) return Optional.empty();
        log.debug("Node-ID cache hit: provider={}", provider);
        return Optional.of(new OwnerRepo(doc.getString("repo_owner"), doc.getString("repo_name")));
    }

    @Override
    public void store(String provider, String nodeId, OwnerRepo ownerRepo) {
        Document doc = new Document("provider", provider)
                .append("node_id", nodeId)
                .append("repo_owner", ownerRepo.owner())
                .append("repo_name", ownerRepo.name())
                .append("cached_at", Date.from(Instant.now()));
        getCollection()
                .replaceOne(
                        Filters.and(Filters.eq("provider", provider), Filters.eq("node_id", nodeId)),
                        doc,
                        new ReplaceOptions().upsert(true));
        log.debug("Node-ID cached: provider={}, owner={}, name={}", provider, ownerRepo.owner(), ownerRepo.name());
    }

    private MongoCollection<Document> getCollection() {
        return database.getCollection(COLLECTION_NAME);
    }
}
