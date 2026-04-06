package org.finos.gitproxy.user;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.finos.gitproxy.service.JdbcScmTokenCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC-backed {@link UserStore}. Works with H2 and PostgreSQL via the shared {@code schema.sql} schema
 * ({@code proxy_users}, {@code user_emails}, {@code user_scm_identities}).
 *
 * <p>Users are loaded from the YAML config into this store on startup via {@link #upsertAll(List)}. Passwords set in
 * the DB after initial seeding are preserved (upsert is INSERT … ON CONFLICT DO NOTHING for the password column).
 */
public class JdbcUserStore implements MutableUserStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserStore.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcScmTokenCache tokenCache;

    public JdbcUserStore(DataSource dataSource, JdbcScmTokenCache tokenCache) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.tokenCache = tokenCache;
    }

    /**
     * Seed users from the static config. Inserts rows that do not already exist; leaves existing rows untouched so
     * manually updated passwords survive server restarts.
     */
    public void upsertAll(List<UserEntry> users) {
        for (UserEntry u : users) {
            // Insert user only if missing — preserve existing password so manual changes survive restarts.
            // Check-then-insert avoids dialect-specific ON CONFLICT syntax (H2/Postgres differ).
            boolean exists = !jdbc.queryForList(
                            "SELECT username FROM proxy_users WHERE username = :username",
                            Map.of("username", u.getUsername()),
                            String.class)
                    .isEmpty();
            String roles = u.getRoles().isEmpty() ? "USER" : String.join(",", u.getRoles());
            if (!exists) {
                jdbc.update(
                        "INSERT INTO proxy_users (username, password_hash, roles) VALUES (:username, :hash, :roles)",
                        Map.of("username", u.getUsername(), "hash", u.getPasswordHash(), "roles", roles));
            } else {
                // Roles are authoritative from YAML — update on every startup so changes take effect
                // on next restart without requiring a DB migration.
                jdbc.update(
                        "UPDATE proxy_users SET roles = :roles WHERE username = :username",
                        Map.of("username", u.getUsername(), "roles", roles));
            }

            // Always replace emails + scm-identities so YAML stays authoritative for those.
            // DELETE first means plain INSERT is safe — no conflict possible.
            jdbc.update("DELETE FROM user_emails WHERE username = :u", Map.of("u", u.getUsername()));
            for (String email : u.getEmails()) {
                jdbc.update(
                        "INSERT INTO user_emails (username, email) VALUES (:u, :email)",
                        Map.of("u", u.getUsername(), "email", email.toLowerCase()));
            }

            jdbc.update("DELETE FROM user_scm_identities WHERE username = :u", Map.of("u", u.getUsername()));
            for (ScmIdentity id : u.getScmIdentities()) {
                jdbc.update(
                        "INSERT INTO user_scm_identities (username, provider, scm_username)"
                                + " VALUES (:u, :provider, :scmUsername)",
                        Map.of("u", u.getUsername(), "provider", id.getProvider(), "scmUsername", id.getUsername()));
            }

            log.debug("Seeded user '{}' (exists={})", u.getUsername(), exists);
        }
    }

    @Override
    public Optional<UserEntry> findByUsername(String username) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT password_hash, roles FROM proxy_users WHERE username = :u", Map.of("u", username));
        if (rows.isEmpty()) return Optional.empty();
        String hash = (String) rows.get(0).get("password_hash");
        String rolesStr = (String) rows.get(0).get("roles");
        return Optional.of(buildEntry(username, hash, rolesStr));
    }

    @Override
    public Optional<UserEntry> findByEmail(String email) {
        if (email == null) return Optional.empty();
        List<String> rows = jdbc.queryForList(
                "SELECT username FROM user_emails WHERE email = :email",
                Map.of("email", email.toLowerCase()),
                String.class);
        if (rows.isEmpty()) return Optional.empty();
        return findByUsername(rows.get(0));
    }

    @Override
    public Optional<UserEntry> findByScmIdentity(String provider, String scmUsername) {
        if (provider == null || scmUsername == null) return Optional.empty();
        List<String> rows = jdbc.queryForList(
                "SELECT username FROM user_scm_identities WHERE provider = :provider AND scm_username = :scmUsername",
                Map.of("provider", provider, "scmUsername", scmUsername),
                String.class);
        if (rows.isEmpty()) return Optional.empty();
        return findByUsername(rows.get(0));
    }

    @Override
    public List<UserEntry> findAll() {
        List<String> usernames =
                jdbc.queryForList("SELECT username FROM proxy_users ORDER BY username", Map.of(), String.class);
        return usernames.stream().flatMap(u -> findByUsername(u).stream()).toList();
    }

    /** Returns all email entries for a user with their verified status, locked flag, and source, ordered by email. */
    public List<Map<String, Object>> findEmailsWithVerified(String username) {
        return jdbc
                .queryForList(
                        "SELECT email, verified, locked, auth_source FROM user_emails WHERE username = :u ORDER BY email",
                        Map.of("u", username))
                .stream()
                .<Map<String, Object>>map(row -> Map.of(
                        "email", row.get("email"),
                        "verified", Boolean.TRUE.equals(row.get("verified")),
                        "locked", Boolean.TRUE.equals(row.get("locked")),
                        "source", row.get("auth_source") != null ? row.get("auth_source") : "local"))
                .toList();
    }

    /** Returns all SCM identity entries for a user with their verified status. */
    public List<Map<String, Object>> findScmIdentitiesWithVerified(String username) {
        return jdbc
                .queryForList(
                        "SELECT provider, scm_username, verified FROM user_scm_identities WHERE username = :u",
                        Map.of("u", username))
                .stream()
                .<Map<String, Object>>map(row -> Map.of(
                        "provider", row.get("provider"),
                        "username", row.get("scm_username"),
                        "verified", Boolean.TRUE.equals(row.get("verified")),
                        "source", "local"))
                .toList();
    }

    @Override
    public void addEmail(String username, String email) {
        jdbc.update(
                "INSERT INTO user_emails (username, email) VALUES (:u, :email)",
                Map.of("u", username, "email", email.toLowerCase()));
        log.debug("Added email '{}' for user '{}'", email, username);
    }

    @Override
    public void removeEmail(String username, String email) {
        String normalized = email.toLowerCase();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT locked FROM user_emails WHERE username = :u AND email = :email",
                Map.of("u", username, "email", normalized));
        if (!rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("locked"))) {
            throw new LockedEmailException(email);
        }
        jdbc.update(
                "DELETE FROM user_emails WHERE username = :u AND email = :email",
                Map.of("u", username, "email", normalized));
        log.debug("Removed email '{}' for user '{}'", email, username);
    }

    @Override
    public void addScmIdentity(String username, String provider, String scmUsername) {
        List<String> existing = jdbc.queryForList(
                "SELECT username FROM user_scm_identities WHERE provider = :provider AND scm_username = :scmUsername",
                Map.of("provider", provider, "scmUsername", scmUsername),
                String.class);
        if (!existing.isEmpty()) {
            String owner = existing.get(0);
            if (owner.equals(username)) return; // already registered to this user — no-op
            throw new ScmIdentityConflictException(provider, scmUsername, owner);
        }
        jdbc.update(
                "INSERT INTO user_scm_identities (username, provider, scm_username) VALUES (:u, :provider, :scmUsername)",
                Map.of("u", username, "provider", provider, "scmUsername", scmUsername));
        log.debug("Added SCM identity '{}/{}' for user '{}'", provider, scmUsername, username);
        tokenCache.evictByUsername(provider, username);
    }

    @Override
    public void removeScmIdentity(String username, String provider, String scmUsername) {
        jdbc.update(
                "DELETE FROM user_scm_identities WHERE username = :u AND provider = :provider AND scm_username = :scmUsername",
                Map.of("u", username, "provider", provider, "scmUsername", scmUsername));
        log.debug("Removed SCM identity '{}/{}' for user '{}'", provider, scmUsername, username);
        tokenCache.evictByUsername(provider, username);
    }

    /**
     * Ensures a user row exists for IdP-authenticated users who are not in the YAML config. No-op if already present.
     * The password is left NULL so the account cannot be used for form login.
     */
    public void upsertUser(String username) {
        boolean exists = !jdbc.queryForList(
                        "SELECT username FROM proxy_users WHERE username = :u", Map.of("u", username), String.class)
                .isEmpty();
        if (!exists) {
            jdbc.update(
                    "INSERT INTO proxy_users (username, password_hash, roles) VALUES (:u, NULL, 'USER')",
                    Map.of("u", username));
            log.debug("Auto-provisioned IdP user '{}'", username);
        }
    }

    /**
     * Create a new local user. Throws {@link IllegalArgumentException} if the username already exists.
     *
     * @param username proxy username
     * @param passwordHash encoded password (e.g. {@code {bcrypt}$2a$...} or {@code {noop}plain})
     * @param roles comma-separated roles string, e.g. {@code "USER"} or {@code "USER,ADMIN"}
     */
    public void createUser(String username, String passwordHash, String roles) {
        boolean exists = !jdbc.queryForList(
                        "SELECT username FROM proxy_users WHERE username = :u", Map.of("u", username), String.class)
                .isEmpty();
        if (exists) {
            throw new IllegalArgumentException("User already exists: " + username);
        }
        jdbc.update(
                "INSERT INTO proxy_users (username, password_hash, roles) VALUES (:u, :hash, :roles)",
                Map.of("u", username, "hash", passwordHash, "roles", roles));
        log.info("Created user '{}'", username);
    }

    /**
     * Delete a user and all their associated emails, SCM identities, and cached tokens (cascade).
     *
     * @throws IllegalArgumentException if the user does not exist
     */
    public void deleteUser(String username) {
        int deleted = jdbc.update("DELETE FROM proxy_users WHERE username = :u", Map.of("u", username));
        if (deleted == 0) {
            throw new IllegalArgumentException("User not found: " + username);
        }
        log.info("Deleted user '{}'", username);
    }

    /**
     * Update the password hash for an existing user.
     *
     * @throws IllegalArgumentException if the user does not exist
     */
    public void setPassword(String username, String passwordHash) {
        int updated = jdbc.update(
                "UPDATE proxy_users SET password_hash = :hash WHERE username = :u",
                Map.of("u", username, "hash", passwordHash));
        if (updated == 0) {
            throw new IllegalArgumentException("User not found: " + username);
        }
        log.info("Updated password for user '{}'", username);
    }

    /**
     * Inserts or updates an email for a user as locked (owned by the identity provider). On conflict the row is updated
     * so the source and locked flag stay in sync with the current IdP configuration.
     */
    public void upsertLockedEmail(String username, String email, String authSource) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT locked FROM user_emails WHERE username = :u AND email = :email",
                Map.of("u", username, "email", email));
        if (rows.isEmpty()) {
            jdbc.update(
                    "INSERT INTO user_emails (username, email, verified, auth_source, locked) VALUES (:u, :email, TRUE, :source, TRUE)",
                    Map.of("u", username, "email", email, "source", authSource));
        } else {
            jdbc.update(
                    "UPDATE user_emails SET verified = TRUE, auth_source = :source, locked = TRUE WHERE username = :u AND email = :email",
                    Map.of("u", username, "email", email, "source", authSource));
        }
        log.debug("Upserted locked email '{}' ({}) for user '{}'", email, authSource, username);
    }

    private UserEntry buildEntry(String username, String passwordHash, String rolesStr) {
        List<String> emails = jdbc.queryForList(
                "SELECT email FROM user_emails WHERE username = :u ORDER BY email",
                Map.of("u", username),
                String.class);
        List<Map<String, Object>> scmRows = jdbc.queryForList(
                "SELECT provider, scm_username FROM user_scm_identities WHERE username = :u", Map.of("u", username));
        List<ScmIdentity> scmIdentities = scmRows.stream()
                .map(r -> ScmIdentity.builder()
                        .provider((String) r.get("provider"))
                        .username((String) r.get("scm_username"))
                        .build())
                .toList();
        List<String> roles = (rolesStr != null && !rolesStr.isBlank()) ? List.of(rolesStr.split(",")) : List.of("USER");
        return UserEntry.builder()
                .username(username)
                .passwordHash(passwordHash)
                .emails(Collections.unmodifiableList(emails))
                .scmIdentities(Collections.unmodifiableList(scmIdentities))
                .roles(roles)
                .build();
    }
}
