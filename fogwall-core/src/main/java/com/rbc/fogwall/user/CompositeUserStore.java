package com.rbc.fogwall.user;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link UserStore} that combines a read-only config store (YAML-defined users) with a mutable backend store
 * (dynamically created users). Config users serve as break-glass accounts: they are never written to the database, so
 * there are no stale duplicates across restarts and role/credential changes in YAML take effect on restart.
 *
 * <ul>
 *   <li>Reads check the config store first, then fall back to the mutable store.
 *   <li>Writes ({@link #createUser}, {@link #addEmail}, etc.) delegate only to the mutable store.
 *   <li>{@link #findAll()} returns a merged list; config users take precedence on username collision.
 * </ul>
 */
public class CompositeUserStore implements UserStore {

    private final ReadOnlyUserStore configStore;
    private final UserStore mutableStore;

    public CompositeUserStore(ReadOnlyUserStore configStore, UserStore mutableStore) {
        this.configStore = configStore;
        this.mutableStore = mutableStore;
    }

    // ── reads — config first, mutable-store fallback ────────────────────────────

    @Override
    public Optional<UserEntry> findByUsername(String username) {
        Optional<UserEntry> fromConfig = configStore.findByUsername(username);
        if (fromConfig.isEmpty()) {
            return mutableStore.findByUsername(username);
        }
        return Optional.of(mergeMutableFields(fromConfig.get(), mutableStore.findByUsername(username)));
    }

    @Override
    public Optional<UserEntry> findByEmail(String email) {
        Optional<UserEntry> fromConfig = configStore.findByEmail(email);
        if (fromConfig.isPresent()) {
            UserEntry cfg = fromConfig.get();
            return Optional.of(mergeMutableFields(cfg, mutableStore.findByUsername(cfg.getUsername())));
        }
        return mutableStore.findByEmail(email).map(this::mergeConfigFields);
    }

    @Override
    public Optional<UserEntry> findByScmIdentity(String provider, String scmUsername) {
        Optional<UserEntry> fromConfig = configStore.findByScmIdentity(provider, scmUsername);
        if (fromConfig.isPresent()) {
            UserEntry cfg = fromConfig.get();
            return Optional.of(mergeMutableFields(cfg, mutableStore.findByUsername(cfg.getUsername())));
        }
        return mutableStore.findByScmIdentity(provider, scmUsername).map(this::mergeConfigFields);
    }

    @Override
    public List<UserEntry> findAll() {
        // Config wins on username/passwordHash/roles, but the additive fields still merge — the same entry
        // findByUsername returns. Returning the bare config snapshot here made a config-declared user who had linked
        // an account look unlinked to anything that enumerates users, which is how the SSH key refresh came to skip
        // every such user.
        Map<String, UserEntry> fromMutable = new LinkedHashMap<>();
        for (UserEntry u : mutableStore.findAll()) {
            fromMutable.put(u.getUsername(), u);
        }
        Map<String, UserEntry> merged = new LinkedHashMap<>();
        for (UserEntry u : configStore.findAll()) {
            merged.put(u.getUsername(), mergeMutableFields(u, Optional.ofNullable(fromMutable.get(u.getUsername()))));
        }
        fromMutable.forEach(merged::putIfAbsent);
        return new ArrayList<>(merged.values());
    }

    // ── enriched queries — JDBC if the user is there, config fallback ────────────

    @Override
    public List<Map<String, Object>> findEmailsWithVerified(String username) {
        var configUser = configStore.findByUsername(username);
        List<Map<String, Object>> result = new ArrayList<>();

        // JDBC rows, keyed by email. A config-declared email can ALSO have a JDBC row: OAuth linking upserts the
        // addresses a provider reports as verified, under the same username. The config entry stays locked, but its
        // verified flag and provider sources come from that row — otherwise linking looks like it did nothing.
        Map<String, Map<String, Object>> jdbcByEmail = new LinkedHashMap<>();
        if (mutableStore.findByUsername(username).isPresent()) {
            mutableStore.findEmailsWithVerified(username).forEach(e -> jdbcByEmail.put((String) e.get("email"), e));
        }

        // Config emails are always included as locked
        configUser.ifPresent(u -> u.getEmails().forEach(e -> {
            Map<String, Object> row = jdbcByEmail.get(e);
            boolean verified = row != null && Boolean.TRUE.equals(row.get("verified"));
            result.add(Map.of(
                    "email",
                    e,
                    "verified",
                    verified,
                    "locked",
                    true,
                    "source",
                    verified ? row.get("source") : "config"));
        }));

        // Supplemental JDBC emails (skip any that overlap with config)
        Set<String> configEmails =
                configUser.<Set<String>>map(u -> new HashSet<>(u.getEmails())).orElse(Set.of());
        jdbcByEmail.values().stream()
                .filter(e -> !configEmails.contains(e.get("email")))
                .forEach(result::add);

        return result;
    }

    @Override
    public List<Map<String, Object>> findScmIdentitiesWithVerified(String username) {
        var configUser = configStore.findByUsername(username);
        List<Map<String, Object>> result = new ArrayList<>();

        // JDBC rows keyed by provider:username. Same collision as emails: linking a provider account whose login is
        // already config-declared marks the JDBC row verified; the config entry must reflect that or the profile
        // page reports the account as "Not linked" right after a successful link.
        Map<String, Map<String, Object>> jdbcByKey = new LinkedHashMap<>();
        if (mutableStore.findByUsername(username).isPresent()) {
            mutableStore
                    .findScmIdentitiesWithVerified(username)
                    .forEach(id -> jdbcByKey.put(id.get("provider") + ":" + id.get("username"), id));
        }

        // Config identities are always included as locked
        configUser.ifPresent(u -> u.getScmIdentities().stream()
                .filter(id -> !"proxy".equals(id.getProvider()))
                .forEach(id -> {
                    Map<String, Object> row = jdbcByKey.get(id.getProvider() + ":" + id.getUsername());
                    result.add(Map.of(
                            "provider",
                            id.getProvider(),
                            "username",
                            id.getUsername(),
                            "verified",
                            row != null && Boolean.TRUE.equals(row.get("verified")),
                            "source",
                            "config"));
                }));

        // Supplemental JDBC identities (skip any that overlap with config)
        Set<String> configKeys = configUser
                .map(u -> u.getScmIdentities().stream()
                        .map(id -> id.getProvider() + ":" + id.getUsername())
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
        jdbcByKey.values().stream()
                .filter(id -> !configKeys.contains(id.get("provider") + ":" + id.get("username")))
                .forEach(result::add);

        return result;
    }

    // ── writes ──────────────────────────────────────────────────────────────────
    // Config users can add supplemental emails/identities (stored in JDBC).
    // Config-defined values are locked and cannot be removed.

    @Override
    public void addEmail(String username, String email) {
        var configUser = configStore.findByUsername(username);
        if (configUser.isPresent()) {
            if (configUser.get().getEmails().contains(email)) return; // already present, no-op
            mutableStore.upsertUser(username); // ensure JDBC row exists for supplemental data
        }
        mutableStore.addEmail(username, email);
    }

    @Override
    public void removeEmail(String username, String email) {
        configStore.findByUsername(username).ifPresent(u -> {
            if (u.getEmails().contains(email)) throw new LockedEmailException(email);
        });
        mutableStore.removeEmail(username, email);
    }

    @Override
    public void removeEmailsByAuthSource(String username, String authSource) {
        mutableStore.removeEmailsByAuthSource(username, authSource);
    }

    @Override
    public void addScmIdentity(String username, String provider, String scmUsername) {
        var configUser = configStore.findByUsername(username);
        if (configUser.isPresent()) {
            boolean alreadyInConfig = configUser.get().getScmIdentities().stream()
                    .anyMatch(id -> id.getProvider().equals(provider)
                            && id.getUsername().equals(scmUsername));
            if (alreadyInConfig) return; // no-op
            mutableStore.upsertUser(username);
        }
        mutableStore.addScmIdentity(username, provider, scmUsername);
    }

    @Override
    public void removeScmIdentity(String username, String provider, String scmUsername) {
        configStore.findByUsername(username).ifPresent(u -> {
            boolean inConfig = u.getScmIdentities().stream()
                    .anyMatch(id -> id.getProvider().equals(provider)
                            && id.getUsername().equals(scmUsername));
            if (inConfig) throw new LockedByConfigException(username);
        });
        mutableStore.removeScmIdentity(username, provider, scmUsername);
    }

    @Override
    public void upsertVerifiedScmIdentity(String username, String provider, String scmUsername) {
        mutableStore.upsertVerifiedScmIdentity(username, provider, scmUsername);
    }

    @Override
    public void removeVerifiedScmIdentity(String username, String provider) {
        mutableStore.removeVerifiedScmIdentity(username, provider);
    }

    @Override
    public void createUser(String username, String passwordHash, String roles) {
        mutableStore.createUser(username, passwordHash, roles);
    }

    @Override
    public void deleteUser(String username) {
        mutableStore.deleteUser(username);
    }

    @Override
    public void setPassword(String username, String passwordHash) {
        mutableStore.setPassword(username, passwordHash);
    }

    @Override
    public void upsertUser(String username) {
        mutableStore.upsertUser(username);
    }

    @Override
    public void upsertUser(String username, List<String> roles) {
        mutableStore.upsertUser(username, roles);
    }

    @Override
    public void upsertLockedEmail(String username, String email, String authSource) {
        mutableStore.upsertLockedEmail(username, email, authSource);
    }

    // ── SSH key management ───────────────────────────────────────────────────────

    @Override
    public Optional<UserEntry> findBySshFingerprint(String fingerprint) {
        Optional<UserEntry> fromConfig = configStore.findBySshFingerprint(fingerprint);
        if (fromConfig.isPresent()) {
            UserEntry cfg = fromConfig.get();
            return Optional.of(mergeMutableFields(cfg, mutableStore.findByUsername(cfg.getUsername())));
        }
        return mutableStore.findBySshFingerprint(fingerprint).map(this::mergeConfigFields);
    }

    /**
     * Merges config-sourced emails/scmIdentities/sshKeys onto a {@link UserEntry} resolved from the mutable store, for
     * a username that is also config-defined.
     *
     * <p>Needed because lookups keyed by something other than username (e.g. an SSH key fingerprint added via the
     * dashboard, not YAML) can resolve a config-defined user via the mutable store's own fingerprint index — but the
     * raw mutable-store record doesn't carry that username's config-only supplemental data (e.g.
     * {@code scm-identities}), unlike {@link #findScmIdentitiesWithVerified} and friends which already merge correctly
     * by username. Without this, e.g. SSH auth via a dashboard-added key resolves a user with no linked SCM identities
     * even though the config declares them.
     */
    private UserEntry mergeConfigFields(UserEntry fromMutable) {
        Optional<UserEntry> configUser = configStore.findByUsername(fromMutable.getUsername());
        if (configUser.isEmpty()) {
            return fromMutable;
        }
        UserEntry cfg = configUser.get();

        List<String> mergedEmails = new ArrayList<>(cfg.getEmails());
        fromMutable.getEmails().stream()
                .filter(e -> !cfg.getEmails().contains(e))
                .forEach(mergedEmails::add);

        List<ScmIdentity> mergedScmIdentities = mergeScmIdentities(cfg, fromMutable);
        List<SshKeyEntry> mergedSshKeys = mergeSshKeys(cfg, fromMutable);

        return UserEntry.builder()
                .username(fromMutable.getUsername())
                .passwordHash(fromMutable.getPasswordHash())
                .emails(mergedEmails)
                .scmIdentities(mergedScmIdentities)
                .sshKeys(mergedSshKeys)
                .roles(fromMutable.getRoles())
                .build();
    }

    /**
     * Config identities first, then mutable-only ones. On a provider:login collision the mutable row wins when it is
     * OAuth-verified: linking an account whose login is already config-declared is exactly how a config user gets a
     * verified identity, and strict identity mode (#40) only honours verified ones — the config snapshot must not mask
     * it.
     */
    private static List<ScmIdentity> mergeScmIdentities(UserEntry cfg, UserEntry mutable) {
        Map<String, ScmIdentity> mutableVerified = new LinkedHashMap<>();
        mutable.getScmIdentities().stream()
                .filter(ScmIdentity::isVerified)
                .forEach(id -> mutableVerified.put(id.getProvider() + ":" + id.getUsername(), id));
        Set<String> configKeys = cfg.getScmIdentities().stream()
                .map(id -> id.getProvider() + ":" + id.getUsername())
                .collect(Collectors.toSet());
        List<ScmIdentity> merged = new ArrayList<>();
        cfg.getScmIdentities()
                .forEach(id -> merged.add(mutableVerified.getOrDefault(id.getProvider() + ":" + id.getUsername(), id)));
        mutable.getScmIdentities().stream()
                .filter(id -> !configKeys.contains(id.getProvider() + ":" + id.getUsername()))
                .forEach(merged::add);
        return merged;
    }

    /**
     * Same shape for SSH keys: a config-declared key that a linked provider also reports keeps the provider-sourced
     * (locked, verified) mutable entry rather than the bare config one.
     */
    private static List<SshKeyEntry> mergeSshKeys(UserEntry cfg, UserEntry mutable) {
        Map<String, SshKeyEntry> mutableLocked = new LinkedHashMap<>();
        mutable.getSshKeys().stream()
                .filter(SshKeyEntry::isLocked)
                .forEach(k -> mutableLocked.put(k.getFingerprint(), k));
        Set<String> configFingerprints =
                cfg.getSshKeys().stream().map(SshKeyEntry::getFingerprint).collect(Collectors.toSet());
        List<SshKeyEntry> merged = new ArrayList<>();
        cfg.getSshKeys().forEach(k -> merged.add(mutableLocked.getOrDefault(k.getFingerprint(), k)));
        mutable.getSshKeys().stream()
                .filter(k -> !configFingerprints.contains(k.getFingerprint()))
                .forEach(merged::add);
        return merged;
    }

    /**
     * Merges a config-defined user's additive fields (emails/scmIdentities/sshKeys) with the same username's
     * supplemental data from the mutable store — the mirror of {@link #mergeConfigFields}, for lookups that resolved
     * via the config store first. Config's own username/passwordHash/roles stay authoritative, per this class's
     * invariant that YAML changes take effect on restart.
     *
     * <p>Without this, any lookup that finds a config-defined user (by username, email, SCM identity, or SSH
     * fingerprint) returns a config-only snapshot that never reflects supplemental data written to the mutable store
     * under the same username (e.g. #40's OAuth-imported emails/SSH keys, or a verified SCM identity) — silently
     * breaking commit attribution checks and strict identity mode for any config-declared user (such as a break-glass
     * {@code admin} account) who links an OAuth account.
     */
    private static UserEntry mergeMutableFields(UserEntry cfg, Optional<UserEntry> fromMutable) {
        if (fromMutable.isEmpty()) {
            return cfg;
        }
        UserEntry mutable = fromMutable.get();

        List<String> mergedEmails = new ArrayList<>(cfg.getEmails());
        mutable.getEmails().stream().filter(e -> !cfg.getEmails().contains(e)).forEach(mergedEmails::add);

        List<ScmIdentity> mergedScmIdentities = mergeScmIdentities(cfg, mutable);
        List<SshKeyEntry> mergedSshKeys = mergeSshKeys(cfg, mutable);

        return UserEntry.builder()
                .username(cfg.getUsername())
                .passwordHash(cfg.getPasswordHash())
                .emails(mergedEmails)
                .scmIdentities(mergedScmIdentities)
                .sshKeys(mergedSshKeys)
                .roles(cfg.getRoles())
                .build();
    }

    @Override
    public SshKeyEntry addSshKey(
            String username, String fingerprint, String publicKey, String label, boolean locked, String authSource) {
        // Config-declared keys cannot be re-added via the dashboard
        configStore.findByUsername(username).ifPresent(u -> {
            boolean inConfig =
                    u.getSshKeys().stream().anyMatch(k -> k.getFingerprint().equals(fingerprint));
            if (inConfig) throw new SshKeyConflictException(fingerprint, username);
        });
        if (configStore.findByUsername(username).isPresent()) {
            mutableStore.upsertUser(username); // ensure DB row exists for config users
        }
        return mutableStore.addSshKey(username, fingerprint, publicKey, label, locked, authSource);
    }

    @Override
    public void removeSshKeysByAuthSource(String username, String authSource) {
        mutableStore.removeSshKeysByAuthSource(username, authSource);
    }

    @Override
    public void removeSshKey(String username, String keyId) {
        // Block removal of config-locked keys
        configStore.findByUsername(username).ifPresent(u -> {
            boolean inConfig = u.getSshKeys().stream().anyMatch(k -> k.getId().equals(keyId));
            if (inConfig) throw new LockedByConfigException(username);
        });
        mutableStore.removeSshKey(username, keyId);
    }

    @Override
    public List<SshKeyEntry> findSshKeys(String username) {
        var configUser = configStore.findByUsername(username);
        List<SshKeyEntry> result = new ArrayList<>();

        // Config SSH keys are always included as locked
        configUser.ifPresent(u -> result.addAll(u.getSshKeys()));

        // Supplemental DB keys (skip any whose fingerprint overlaps with config)
        Set<String> configFingerprints = configUser
                .map(u ->
                        u.getSshKeys().stream().map(SshKeyEntry::getFingerprint).collect(Collectors.toSet()))
                .orElse(Set.of());
        if (mutableStore.findByUsername(username).isPresent()) {
            mutableStore.findSshKeys(username).stream()
                    .filter(k -> !configFingerprints.contains(k.getFingerprint()))
                    .forEach(result::add);
        }

        return result;
    }
}
