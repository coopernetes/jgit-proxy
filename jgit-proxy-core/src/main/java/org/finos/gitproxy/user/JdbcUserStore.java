package org.finos.gitproxy.user;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC-backed {@link UserStore}. Works with H2, SQLite, and PostgreSQL via the shared {@code schema.sql} schema
 * ({@code proxy_users}, {@code user_emails}, {@code user_scm_identities}).
 *
 * <p>Users are loaded from the YAML config into this store on startup via {@link #upsertAll(List)}. Passwords set in
 * the DB after initial seeding are preserved (upsert is INSERT … ON CONFLICT DO NOTHING for the password column).
 */
public class JdbcUserStore implements MutableUserStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserStore.class);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Seed users from the static config. Inserts rows that do not already exist; leaves existing rows untouched so
     * manually updated passwords survive server restarts.
     */
    public void upsertAll(List<UserEntry> users) {
        for (UserEntry u : users) {
            // Insert user only if missing — preserve existing password so manual changes survive restarts.
            // Check-then-insert avoids dialect-specific ON CONFLICT syntax (H2/SQLite/Postgres differ).
            boolean exists = !jdbc.queryForList(
                            "SELECT username FROM proxy_users WHERE username = :username",
                            Map.of("username", u.getUsername()),
                            String.class)
                    .isEmpty();
            if (!exists) {
                jdbc.update(
                        "INSERT INTO proxy_users (username, password_hash) VALUES (:username, :hash)",
                        Map.of("username", u.getUsername(), "hash", u.getPasswordHash()));
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
        List<String> rows = jdbc.queryForList(
                "SELECT password_hash FROM proxy_users WHERE username = :u", Map.of("u", username), String.class);
        if (rows.isEmpty()) return Optional.empty();
        String hash = rows.get(0);
        return Optional.of(buildEntry(username, hash));
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
                        "verified", Boolean.TRUE.equals(row.get("verified"))))
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
        jdbc.update(
                "INSERT INTO user_scm_identities (username, provider, scm_username) VALUES (:u, :provider, :scmUsername)",
                Map.of("u", username, "provider", provider, "scmUsername", scmUsername));
        log.debug("Added SCM identity '{}/{}' for user '{}'", provider, scmUsername, username);
    }

    @Override
    public void removeScmIdentity(String username, String provider, String scmUsername) {
        jdbc.update(
                "DELETE FROM user_scm_identities WHERE username = :u AND provider = :provider AND scm_username = :scmUsername",
                Map.of("u", username, "provider", provider, "scmUsername", scmUsername));
        log.debug("Removed SCM identity '{}/{}' for user '{}'", provider, scmUsername, username);
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
            jdbc.update("INSERT INTO proxy_users (username, password_hash) VALUES (:u, NULL)", Map.of("u", username));
            log.debug("Auto-provisioned IdP user '{}'", username);
        }
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

    private UserEntry buildEntry(String username, String passwordHash) {
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
        return UserEntry.builder()
                .username(username)
                .passwordHash(passwordHash)
                .emails(Collections.unmodifiableList(emails))
                .scmIdentities(Collections.unmodifiableList(scmIdentities))
                .build();
    }
}
