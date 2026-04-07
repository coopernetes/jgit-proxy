package org.finos.gitproxy.user;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link MutableUserStore} that combines a read-only config store (YAML-defined users) with a mutable JDBC store
 * (dynamically created users). Config users serve as break-glass accounts: they are never written to the database, so
 * there are no stale duplicates across restarts and role/credential changes in YAML take effect on restart.
 *
 * <ul>
 *   <li>Reads check the config store first, then fall back to the JDBC store.
 *   <li>Writes ({@link #createUser}, {@link #addEmail}, etc.) delegate only to the JDBC store.
 *   <li>{@link #findAll()} returns a merged list; config users take precedence on username collision.
 * </ul>
 */
public class CompositeUserStore implements MutableUserStore {

    private final UserStore configStore;
    private final JdbcUserStore jdbcStore;

    public CompositeUserStore(UserStore configStore, JdbcUserStore jdbcStore) {
        this.configStore = configStore;
        this.jdbcStore = jdbcStore;
    }

    // ── reads — config first, JDBC fallback ─────────────────────────────────────

    @Override
    public Optional<UserEntry> findByUsername(String username) {
        Optional<UserEntry> fromConfig = configStore.findByUsername(username);
        return fromConfig.isPresent() ? fromConfig : jdbcStore.findByUsername(username);
    }

    @Override
    public Optional<UserEntry> findByEmail(String email) {
        Optional<UserEntry> fromConfig = configStore.findByEmail(email);
        return fromConfig.isPresent() ? fromConfig : jdbcStore.findByEmail(email);
    }

    @Override
    public Optional<UserEntry> findByScmIdentity(String provider, String scmUsername) {
        Optional<UserEntry> fromConfig = configStore.findByScmIdentity(provider, scmUsername);
        return fromConfig.isPresent() ? fromConfig : jdbcStore.findByScmIdentity(provider, scmUsername);
    }

    @Override
    public List<UserEntry> findAll() {
        // Merge: config users take precedence on username collision.
        Map<String, UserEntry> merged = new LinkedHashMap<>();
        for (UserEntry u : configStore.findAll()) {
            merged.put(u.getUsername(), u);
        }
        for (UserEntry u : jdbcStore.findAll()) {
            merged.putIfAbsent(u.getUsername(), u);
        }
        return new ArrayList<>(merged.values());
    }

    // ── enriched queries — JDBC if the user is there, config fallback ────────────

    @Override
    public List<Map<String, Object>> findEmailsWithVerified(String username) {
        if (jdbcStore.findByUsername(username).isPresent()) {
            return jdbcStore.findEmailsWithVerified(username);
        }
        return configStore
                .findByUsername(username)
                .map(u -> u.getEmails().stream()
                        .<Map<String, Object>>map(
                                e -> Map.of("email", e, "verified", false, "locked", true, "source", "config"))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<Map<String, Object>> findScmIdentitiesWithVerified(String username) {
        if (jdbcStore.findByUsername(username).isPresent()) {
            return jdbcStore.findScmIdentitiesWithVerified(username);
        }
        return configStore
                .findByUsername(username)
                .map(u -> u.getScmIdentities().stream()
                        .filter(id -> !"proxy".equals(id.getProvider()))
                        .<Map<String, Object>>map(id -> Map.of(
                                "provider",
                                id.getProvider(),
                                "username",
                                id.getUsername(),
                                "verified",
                                false,
                                "source",
                                "config"))
                        .toList())
                .orElse(List.of());
    }

    // ── writes — JDBC only (config users are immutable at runtime) ──────────────

    @Override
    public void addEmail(String username, String email) {
        requireMutable(username);
        jdbcStore.addEmail(username, email);
    }

    @Override
    public void removeEmail(String username, String email) {
        requireMutable(username);
        jdbcStore.removeEmail(username, email);
    }

    @Override
    public void addScmIdentity(String username, String provider, String scmUsername) {
        requireMutable(username);
        jdbcStore.addScmIdentity(username, provider, scmUsername);
    }

    @Override
    public void removeScmIdentity(String username, String provider, String scmUsername) {
        requireMutable(username);
        jdbcStore.removeScmIdentity(username, provider, scmUsername);
    }

    private void requireMutable(String username) {
        if (configStore.findByUsername(username).isPresent()) {
            throw new LockedByConfigException(username);
        }
    }

    @Override
    public void createUser(String username, String passwordHash, String roles) {
        jdbcStore.createUser(username, passwordHash, roles);
    }

    @Override
    public void deleteUser(String username) {
        jdbcStore.deleteUser(username);
    }

    @Override
    public void setPassword(String username, String passwordHash) {
        jdbcStore.setPassword(username, passwordHash);
    }

    @Override
    public void upsertUser(String username) {
        jdbcStore.upsertUser(username);
    }

    @Override
    public void upsertLockedEmail(String username, String email, String authSource) {
        jdbcStore.upsertLockedEmail(username, email, authSource);
    }
}
