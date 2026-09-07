package com.rbc.fogwall.db.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.rbc.fogwall.user.ScmOAuthTokenStore;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.Binary;

/**
 * MongoDB-backed {@link ScmOAuthTokenStore}, the peer of the JDBC {@code user_scm_tokens} table.
 *
 * <p>Token bytes arrive already encrypted and are stored as BSON binary. Nothing here reads or derives key material.
 */
@Slf4j
public class MongoScmOAuthTokenStore implements ScmOAuthTokenStore {

    private static final String COLLECTION_NAME = "user_scm_tokens";

    private final MongoDatabase database;

    public MongoScmOAuthTokenStore(MongoClient client, String databaseName) {
        this.database = client.getDatabase(databaseName);
    }

    /** Creates the uniqueness index the {@code (username, provider)} key depends on. */
    public void initialize() {
        getCollection().createIndex(Indexes.ascending("username", "provider"), new IndexOptions().unique(true));
        log.info("MongoDB SCM OAuth token store initialized");
    }

    @Override
    public void save(
            String username,
            String provider,
            byte[] encryptedAccessToken,
            byte[] encryptedRefreshToken,
            String scopes,
            Instant expiresAt) {
        Document doc = new Document("username", username)
                .append("provider", provider)
                .append("access_token", encryptedAccessToken != null ? new Binary(encryptedAccessToken) : null)
                .append("refresh_token", encryptedRefreshToken != null ? new Binary(encryptedRefreshToken) : null)
                .append("scopes", scopes)
                .append("expires_at", expiresAt != null ? Date.from(expiresAt) : null)
                .append("authorized_at", Date.from(Instant.now()));
        getCollection()
                .replaceOne(
                        Filters.and(Filters.eq("username", username), Filters.eq("provider", provider)),
                        doc,
                        new ReplaceOptions().upsert(true));
        log.debug("Stored OAuth token for user '{}' / provider '{}'", username, provider);
    }

    @Override
    public Optional<byte[]> findAccessToken(String username, String provider) {
        Document doc = getCollection()
                .find(Filters.and(Filters.eq("username", username), Filters.eq("provider", provider)))
                .first();
        if (doc == null) {
            return Optional.empty();
        }
        Binary token = doc.get("access_token", Binary.class);
        return Optional.ofNullable(token).map(Binary::getData);
    }

    @Override
    public void remove(String username, String provider) {
        getCollection().deleteOne(Filters.and(Filters.eq("username", username), Filters.eq("provider", provider)));
        log.debug("Removed OAuth token for user '{}' / provider '{}'", username, provider);
    }

    private MongoCollection<Document> getCollection() {
        return database.getCollection(COLLECTION_NAME);
    }
}
