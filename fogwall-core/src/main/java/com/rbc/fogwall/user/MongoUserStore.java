package com.rbc.fogwall.user;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import com.rbc.fogwall.service.ScmTokenCache;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private static final String COLLECTION_NAME = "proxy_users";

    private final MongoDatabase database;
    private final ScmTokenCache tokenCache;

    public MongoUserStore(MongoClient mongoClient, String databaseName) {
        this(mongoClient, databaseName, null);
    }

    public MongoUserStore(MongoClient mongoClient, String databaseName, ScmTokenCache tokenCache) {
        this.database = mongoClient.getDatabase(databaseName);
        this.tokenCache = tokenCache;
    }

    public void initialize() {
        MongoCollection<Document> col = getCollection();
        col.createIndex(Indexes.ascending("emails.email"));
        col.createIndex(Indexes.ascending("scmIdentities.provider", "scmIdentities.username"));
        col.createIndex(Indexes.ascending("sshKeys.fingerprint"));
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
                .<Map<String, Object>>map(e -> {
                    List<String> sources = e.getList("authSources", String.class, List.of());
                    String sourceLabel = !sources.isEmpty()
                            ? String.join(", ", sources)
                            : (e.getString("authSource") != null ? e.getString("authSource") : "local");
                    return Map.of(
                            "email",
                            e.getString("email"),
                            "verified",
                            Boolean.TRUE.equals(e.getBoolean("verified")),
                            "locked",
                            Boolean.TRUE.equals(e.getBoolean("locked")),
                            "source",
                            sourceLabel);
                })
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
        long permDeleted = database.getCollection("repo_permissions")
                .deleteMany(Filters.eq("username", username))
                .getDeletedCount();
        if (permDeleted > 0) {
            log.info("Deleted user '{}' and {} orphaned permission(s)", username, permDeleted);
        } else {
            log.info("Deleted user '{}'", username);
        }
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
        upsertUser(username, List.of("USER"));
    }

    @Override
    public void upsertUser(String username, List<String> roles) {
        String rolesStr = roles.isEmpty() ? "USER" : String.join(",", roles);
        if (getCollection().find(Filters.eq("_id", username)).first() == null) {
            getCollection()
                    .insertOne(new Document("_id", username)
                            .append("passwordHash", null)
                            .append("roles", rolesStr)
                            .append("emails", List.of())
                            .append("scmIdentities", List.of()));
            log.debug("Auto-provisioned IdP user '{}' with roles {}", username, rolesStr);
        } else {
            getCollection().updateOne(Filters.eq("_id", username), Updates.set("roles", rolesStr));
            log.debug("Synced IdP roles for user '{}': {}", username, rolesStr);
        }
    }

    @Override
    public void addEmail(String username, String email) {
        String normalized = email.toLowerCase();
        Document owner = getCollection()
                .find(Filters.elemMatch("emails", Filters.eq("email", normalized)))
                .projection(new Document("_id", 1))
                .first();
        if (owner != null) {
            String ownerUsername = owner.getString("_id");
            if (ownerUsername.equals(username)) return; // already registered to this user — no-op
            throw new EmailConflictException(normalized, ownerUsername);
        }
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
    public void removeEmailsByAuthSource(String username, String authSource) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return;
        List<Document> emails = doc.getList("emails", Document.class, List.of());
        List<Document> updated = new ArrayList<>();
        for (Document e : emails) {
            List<String> sources = new ArrayList<>(e.getList("authSources", String.class, List.of()));
            if (!sources.remove(authSource)) {
                updated.add(e);
                continue;
            }
            boolean localLocked = "local".equals(e.getString("authSource"));
            if (sources.isEmpty() && !localLocked) {
                continue; // no linked provider claims this email anymore — drop it
            }
            Document next = new Document(e);
            next.put("authSources", sources);
            if (authSource.equals(e.getString("authSource")) && !sources.isEmpty()) {
                next.put("authSource", sources.get(0)); // re-label the primary source shown in the UI
            }
            updated.add(next);
        }
        getCollection().updateOne(Filters.eq("_id", username), Updates.set("emails", updated));
        log.debug("Removed email(s) no longer claimed by any linked provider for user '{}'", username);
    }

    @Override
    public void upsertLockedEmail(String username, String email, String authSource) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return;
        Document owner = getCollection()
                .find(Filters.elemMatch("emails", Filters.eq("email", email)))
                .projection(new Document("_id", 1))
                .first();
        if (owner != null && !owner.getString("_id").equals(username)) {
            throw new EmailConflictException(email, owner.getString("_id"));
        }
        // Same email, possibly already verified by a different provider (#40: an email can legitimately be
        // verified by more than one linked provider) — accumulate sources rather than overwriting them.
        List<Document> emails = doc.getList("emails", Document.class, List.of());
        Document existing = emails.stream()
                .filter(e -> email.equals(e.getString("email")))
                .findFirst()
                .orElse(null);
        List<String> sources = existing != null
                ? new ArrayList<>(existing.getList("authSources", String.class, List.of()))
                : new ArrayList<>();
        if (!sources.contains(authSource)) sources.add(authSource);
        String primarySource = existing != null && existing.getString("authSource") != null
                ? existing.getString("authSource")
                : authSource;

        getCollection().updateOne(Filters.eq("_id", username), Updates.pull("emails", new Document("email", email)));
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.addToSet(
                                "emails",
                                new Document("email", email)
                                        .append("verified", true)
                                        .append("locked", true)
                                        .append("authSource", primarySource)
                                        .append("authSources", sources)));
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
        if (tokenCache != null) tokenCache.evictByUsername(provider, username);
    }

    @Override
    public void removeScmIdentity(String username, String provider, String scmUsername) {
        Document doc = getCollection()
                .find(Filters.and(
                        Filters.eq("_id", username),
                        Filters.elemMatch(
                                "scmIdentities",
                                Filters.and(Filters.eq("provider", provider), Filters.eq("username", scmUsername)))))
                .first();
        if (doc == null) return; // no-op — identity does not exist or does not belong to this user
        boolean verified = doc.getList("scmIdentities", Document.class, List.of()).stream()
                .filter(id -> provider.equals(id.getString("provider")) && scmUsername.equals(id.getString("username")))
                .anyMatch(id -> Boolean.TRUE.equals(id.getBoolean("verified")));
        if (verified) {
            throw new VerifiedScmIdentityException(provider, scmUsername);
        }
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.pull(
                                "scmIdentities", new Document("provider", provider).append("username", scmUsername)));
        log.debug("Removed SCM identity '{}/{}' for user '{}'", provider, scmUsername, username);
        if (tokenCache != null) tokenCache.evictByUsername(provider, username);
    }

    @Override
    public void removeVerifiedScmIdentity(String username, String provider) {
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.pull(
                                "scmIdentities",
                                Filters.and(Filters.eq("provider", provider), Filters.eq("verified", true))));
        log.debug("Unlinked OAuth-verified SCM identity for user '{}' / provider '{}'", username, provider);
        if (tokenCache != null) tokenCache.evictByUsername(provider, username);
    }

    @Override
    public void upsertVerifiedScmIdentity(String username, String provider, String scmUsername) {
        Document existing = getCollection()
                .find(Filters.elemMatch(
                        "scmIdentities",
                        Filters.and(Filters.eq("provider", provider), Filters.eq("username", scmUsername))))
                .first();
        if (existing != null) {
            String owner = existing.getString("_id");
            if (!owner.equals(username)) {
                throw new ScmIdentityConflictException(provider, scmUsername, owner);
            }
        }

        // OAuth linking is one identity per provider — pull any other identity this user has for this provider first.
        getCollection()
                .updateOne(
                        Filters.eq("_id", username), Updates.pull("scmIdentities", new Document("provider", provider)));
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.addToSet(
                                "scmIdentities",
                                new Document("provider", provider)
                                        .append("username", scmUsername)
                                        .append("verified", true)));
        log.debug("Upserted verified SCM identity '{}/{}' for user '{}'", provider, scmUsername, username);
        if (tokenCache != null) tokenCache.evictByUsername(provider, username);
    }

    // ── SSH key management ────────────────────────────────────────────────────────

    @Override
    public Optional<UserEntry> findBySshFingerprint(String fingerprint) {
        if (fingerprint == null) return Optional.empty();
        Document doc = getCollection()
                .find(Filters.elemMatch("sshKeys", Filters.eq("fingerprint", fingerprint)))
                .first();
        return Optional.ofNullable(doc).map(MongoUserStore::fromDocument);
    }

    @Override
    public SshKeyEntry addSshKey(
            String username, String fingerprint, String publicKey, String label, boolean locked, String authSource) {
        Document existing = getCollection()
                .find(Filters.elemMatch("sshKeys", Filters.eq("fingerprint", fingerprint)))
                .first();
        if (existing != null) {
            String owner = existing.getString("_id");
            if (owner.equals(username)) {
                // Same key, already registered to this user — possibly via a different provider (#40: a key can
                // legitimately be verified by more than one linked provider). Record the additional source rather
                // than silently no-opping, so unlinking one provider doesn't delete a key another still claims.
                if (locked && !"config".equals(authSource)) {
                    addSshKeySource(username, fingerprint, authSource);
                }
                return findSshKeys(username).stream()
                        .filter(k -> k.getFingerprint().equals(fingerprint))
                        .findFirst()
                        .orElseThrow();
            }
            throw new SshKeyConflictException(fingerprint, owner);
        }

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<String> initialSources = locked && !"config".equals(authSource) ? List.of(authSource) : List.of();
        getCollection()
                .updateOne(
                        Filters.eq("_id", username),
                        Updates.addToSet(
                                "sshKeys",
                                new Document("id", id)
                                        .append("fingerprint", fingerprint)
                                        .append("publicKey", publicKey)
                                        .append("label", label != null ? label : "")
                                        .append("createdAt", Date.from(now))
                                        .append("locked", locked)
                                        .append("authSource", authSource)
                                        .append("authSources", initialSources)));
        log.info(
                "Added SSH key {} ({}) for user '{}' (locked={}, source={})",
                fingerprint,
                label,
                username,
                locked,
                authSource);
        return SshKeyEntry.builder()
                .id(id)
                .username(username)
                .fingerprint(fingerprint)
                .publicKey(publicKey)
                .label(label)
                .createdAt(now)
                .locked(locked)
                .authSource(authSource)
                .build();
    }

    @Override
    public void removeSshKeysByAuthSource(String username, String authSource) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return;
        List<Document> keys = doc.getList("sshKeys", Document.class, List.of());
        List<Document> updated = new ArrayList<>();
        for (Document key : keys) {
            List<String> sources = new ArrayList<>(key.getList("authSources", String.class, List.of()));
            if (!sources.remove(authSource)) {
                updated.add(key);
                continue;
            }
            boolean configLocked = "config".equals(key.getString("authSource"));
            if (sources.isEmpty() && !configLocked) {
                continue; // no linked provider claims this key anymore — drop it
            }
            Document next = new Document(key);
            next.put("authSources", sources);
            if (authSource.equals(key.getString("authSource")) && !sources.isEmpty()) {
                next.put("authSource", sources.get(0)); // re-label the primary source shown in the UI
            }
            updated.add(next);
        }
        getCollection().updateOne(Filters.eq("_id", username), Updates.set("sshKeys", updated));
        log.debug("Removed SSH key(s) no longer claimed by any linked provider for user '{}'", username);
    }

    @Override
    public void removeSshKeySource(String username, String fingerprint, String authSource) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return;
        List<Document> keys = doc.getList("sshKeys", Document.class, List.of());
        List<Document> updated = new ArrayList<>();
        boolean dropped = false;
        for (Document key : keys) {
            if (!fingerprint.equals(key.getString("fingerprint"))) {
                updated.add(key);
                continue;
            }
            List<String> sources = new ArrayList<>(key.getList("authSources", String.class, List.of()));
            if (!sources.remove(authSource)) {
                updated.add(key);
                continue;
            }
            boolean configLocked = "config".equals(key.getString("authSource"));
            if (sources.isEmpty() && !configLocked) {
                dropped = true;
                continue; // no linked provider claims this key anymore — drop it
            }
            Document next = new Document(key);
            next.put("authSources", sources);
            if (authSource.equals(key.getString("authSource")) && !sources.isEmpty()) {
                next.put("authSource", sources.get(0)); // re-label the primary source shown in the UI
            }
            updated.add(next);
        }
        getCollection().updateOne(Filters.eq("_id", username), Updates.set("sshKeys", updated));
        if (dropped) {
            log.info(
                    "Removed SSH key {} for user '{}' — no longer claimed by any linked provider",
                    fingerprint,
                    username);
        }
    }

    private void addSshKeySource(String username, String fingerprint, String authSource) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return;
        List<Document> keys = doc.getList("sshKeys", Document.class, List.of());
        List<Document> updated = keys.stream()
                .map(key -> {
                    if (!fingerprint.equals(key.getString("fingerprint"))) return key;
                    List<String> sources = new ArrayList<>(key.getList("authSources", String.class, List.of()));
                    if (!sources.contains(authSource)) {
                        sources.add(authSource);
                        log.info("Added '{}' as an additional verified source for SSH key {}", authSource, fingerprint);
                    } else {
                        log.debug("SSH key {} already has '{}' recorded as a source — no-op", fingerprint, authSource);
                    }
                    Document next = new Document(key);
                    next.put("authSources", sources);
                    return next;
                })
                .toList();
        getCollection().updateOne(Filters.eq("_id", username), Updates.set("sshKeys", updated));
    }

    @Override
    public void removeSshKey(String username, String keyId) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return;
        boolean locked = doc.getList("sshKeys", Document.class, List.of()).stream()
                .filter(k -> keyId.equals(k.getString("id")))
                .anyMatch(k -> Boolean.TRUE.equals(k.getBoolean("locked")));
        if (locked) {
            throw new LockedSshKeyException(keyId);
        }
        getCollection().updateOne(Filters.eq("_id", username), Updates.pull("sshKeys", new Document("id", keyId)));
        log.info("Removed SSH key {} for user '{}'", keyId, username);
    }

    @Override
    public List<SshKeyEntry> findSshKeys(String username) {
        Document doc = getCollection().find(Filters.eq("_id", username)).first();
        if (doc == null) return List.of();
        return doc.getList("sshKeys", Document.class, List.of()).stream()
                .map(k -> {
                    List<String> sources = k.getList("authSources", String.class, List.of());
                    String sourceLabel = !sources.isEmpty()
                            ? String.join(", ", sources)
                            : (k.getString("authSource") != null ? k.getString("authSource") : "config");
                    return SshKeyEntry.builder()
                            .id(k.getString("id"))
                            .username(username)
                            .fingerprint(k.getString("fingerprint"))
                            .publicKey(k.getString("publicKey"))
                            .label(k.getString("label"))
                            .createdAt(
                                    k.getDate("createdAt") != null
                                            ? k.getDate("createdAt").toInstant()
                                            : Instant.EPOCH)
                            .locked(Boolean.TRUE.equals(k.getBoolean("locked")))
                            .authSource(sourceLabel)
                            .build();
                })
                .toList();
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
                        .verified(Boolean.TRUE.equals(id.getBoolean("verified")))
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
