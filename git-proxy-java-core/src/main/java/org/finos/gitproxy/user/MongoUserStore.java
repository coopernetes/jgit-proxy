package org.finos.gitproxy.user;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB implementation of {@link UserStore}. Stores each user as a single document with embedded emails and SCM
 * identities.
 *
 * <p>Document shape:
 *
 * <pre>{@code
 * {
 *   "_id": "username",
 *   "passwordHash": "...",
 *   "roles": "USER,ADMIN",
 *   "emails": [
 *     { "email": "user@example.com", "verified": false, "locked": false, "authSource": null }
 *   ],
 *   "scmIdentities": [
 *     { "provider": "github", "username": "coopernetes", "verified": false }
 *   ]
 * }
 * }</pre>
 */
public class MongoUserStore implements UserStore {

    private static final Logger log = LoggerFactory.getLogger(MongoUserStore.class);
    private static final String COLLECTION_NAME = "users";

    private final MongoDatabase database;

    public MongoUserStore(MongoClient mongoClient, String databaseName) {
        this.database = mongoClient.getDatabase(databaseName);
    }

    public void initialize() {
        MongoCollection<Document> col = getCollection();
        col.createIndex(Indexes.ascending("emails.email"));
        col.createIndex(Indexes.ascending("scmIdentities.provider", "scmIdentities.username"));
        log.info("MongoDB user store initialized");
    }

    // ── reads ──────────────────────────────────────────────────────────────────

    @Override
    public Optional<UserEntry> findByUsername(String username) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        return Optional.ofNullable(doc).map(MongoUserStore::fromDocument);
    }

    @Override
    public Optional<UserEntry> findByEmail(String email) {
        if (email == null) return Optional.empty();
        Document doc = getCollection()
                .find(Filters.elemMatch("emails", Filters.eq("email", email.toLowerCase())))
                .first();
        return Optional.ofNullable(doc).map(MongoUserStore::fromDocument);
    }

    @Override
    public Optional<UserEntry> findByScmIdentity(String provider, String scmUsername) {
        if (provider == null || scmUsername == null) return Optional.empty();
        Document doc = getCollection()
                .find(Filters.elemMatch(
                        "scmIdentities",
                        Filters.and(Filters.eq("provider", provider), Filters.eq("username", scmUsername))))
                .first();
        return Optional.ofNullable(doc).map(MongoUserStore::fromDocument);
    }

    @Override
    public List<UserEntry> findAll() {
        List<UserEntry> results = new ArrayList<>();
        getCollection().find().sort(new Document("_id", 1)).forEach(doc -> results.add(fromDocument(doc)));
        return results;
    }

    // ── enriched queries (for admin UI) ────────────────────────────────────────

    @Override
    public List<Map<String, Object>> findEmailsWithVerified(String username) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return List.of();
        List<Document> emails = doc.getList("emails", Document.class, List.of());
        return emails.stream()
                .<Map<String, Object>>map(e -> Map.of(
                        "email",
                        e.getString("email"),
                        "verified",
                        Boolean.TRUE.equals(e.getBoolean("verified")),
                        "locked",
                        Boolean.TRUE.equals(e.getBoolean("locked")),
                        "source",
                        e.getString("authSource") != null ? e.getString("authSource") : "local"))
                .toList();
    }

    @Override
    public List<Map<String, Object>> findScmIdentitiesWithVerified(String username) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return List.of();
        List<Document> identities = doc.getList("scmIdentities", Document.class, List.of());
        return identities.stream()
                .<Map<String, Object>>map(id -> Map.of(
                        "provider", id.getString("provider"),
                        "username", id.getString("username"),
                        "verified", Boolean.TRUE.equals(id.getBoolean("verified")),
                        "source", "local"))
                .toList();
    }

    // ── writes ─────────────────────────────────────────────────────────────────

    @Override
    public void createUser(String username, String passwordHash, String roles) {
        if (getCollection().find(Filters.eq("_id", username)).first() != null) {
            throw new IllegalArgumentException("User already exists: " + username);
        }
        getCollection()
                .insertOne(new Document("_id", username)
                        .append("passwordHash", passwordHash)
                        .append("roles", roles)
                        .append("emails", List.of())
                        .append("scmIdentities", List.of()));
        log.info("Created user '{}'", username);
    }

    @Override
    public void deleteUser(String username) {
        var result = getCollection().deleteOne(Filters.eq("_id", username));
        if (result.getDeletedCount() == 0) {
            throw new IllegalArgumentException("User not found: " + username);
        }
        log.info("Deleted user '{}'", username);
    }

    @Override
    public void setPassword(String username, String passwordHash) {
        var result = getCollection().updateOne(Filters.eq("_id", username), Updates.set("passwordHash", passwordHash));
        if (result.getMatchedCount() == 0) {
            throw new IllegalArgumentException("User not found: " + username);
        }
        log.info("Updated password for user '{}'", username);
    }

    @Override
    public void upsertUser(String username) {
        if (getCollection().find(Filters.eq("_id", username)).first() == null) {
            getCollection()
                    .insertOne(new Document("_id", username)
                            .append("passwordHash", null)
                            .append("roles", "USER")
                            .append("emails", List.of())
                            .append("scmIdentities", List.of()));
            log.debug("Auto-provisioned IdP user '{}'", username);
        }
    }

    @Override
    public void addEmail(String username, String email) {
        String normalized = email.toLowerCase();
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.addToSet(
                                "emails",
                                new Document("email", normalized)
                                        .append("verified", false)
                                        .append("locked", false)
                                        .append("authSource", null)));
        log.debug("Added email '{}' for user '{}'", normalized, username);
    }

    @Override
    public void removeEmail(String username, String email) {
        String normalized = email.toLowerCase();
        // Check locked first
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc != null) {
            List<Document> emails = doc.getList("emails", Document.class, List.of());
            emails.stream()
                    .filter(e -> normalized.equals(e.getString("email")))
                    .filter(e -> Boolean.TRUE.equals(e.getBoolean("locked")))
                    .findFirst()
                    .ifPresent(e -> {
                        throw new LockedEmailException(email);
                    });
        }
        getCollection()
                .updateOne(Filters.eq("_id", username), Updates.pull("emails", new Document("email", normalized)));
        log.debug("Removed email '{}' for user '{}'", normalized, username);
    }

    @Override
    public void upsertLockedEmail(String username, String email, String authSource) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return;
        // Remove existing entry for this email then insert the locked version
        getCollection().updateOne(Filters.eq("_id", username), Updates.pull("emails", new Document("email", email)));
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.addToSet(
                                "emails",
                                new Document("email", email)
                                        .append("verified", true)
                                        .append("locked", true)
                                        .append("authSource", authSource)));
        log.debug("Upserted locked email '{}' ({}) for user '{}'", email, authSource, username);
    }

    @Override
    public void addScmIdentity(String username, String provider, String scmUsername) {
        // Check if this identity is already claimed by another user
        Document existing = getCollection()
                .find(Filters.elemMatch(
                        "scmIdentities",
                        Filters.and(Filters.eq("provider", provider), Filters.eq("username", scmUsername))))
                .first();
        if (existing != null) {
            String owner = existing.getString("_id");
            if (owner.equals(username)) return; // already registered to this user — no-op
            throw new ScmIdentityConflictException(provider, scmUsername, owner);
        }
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.addToSet(
                                "scmIdentities",
                                new Document("provider", provider)
                                        .append("username", scmUsername)
                                        .append("verified", false)));
        log.debug("Added SCM identity '{}/{}' for user '{}'", provider, scmUsername, username);
    }

    @Override
    public void removeScmIdentity(String username, String provider, String scmUsername) {
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.pull(
                                "scmIdentities", new Document("provider", provider).append("username", scmUsername)));
        log.debug("Removed SCM identity '{}/{}' for user '{}'", provider, scmUsername, username);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private MongoCollection<Document> getCollection() {
        return database.getCollection(COLLECTION_NAME);
    }

    private static UserEntry fromDocument(Document doc) {
        List<Document> emailDocs = doc.getList("emails", Document.class, List.of());
        List<String> emails = emailDocs.stream().map(e -> e.getString("email")).toList();

        List<Document> scmDocs = doc.getList("scmIdentities", Document.class, List.of());
        List<ScmIdentity> scmIdentities = scmDocs.stream()
                .map(id -> ScmIdentity.builder()
                        .provider(id.getString("provider"))
                        .username(id.getString("username"))
                        .build())
                .toList();

        String rolesStr = doc.getString("roles");
        List<String> roles = (rolesStr != null && !rolesStr.isBlank()) ? List.of(rolesStr.split(",")) : List.of("USER");

        return UserEntry.builder()
                .username(doc.getString("_id"))
                .passwordHash(doc.getString("passwordHash"))
                .emails(emails)
                .scmIdentities(scmIdentities)
                .roles(roles)
                .build();
    }
}
