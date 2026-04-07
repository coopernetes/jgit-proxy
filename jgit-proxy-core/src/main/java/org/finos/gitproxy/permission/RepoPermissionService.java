package org.finos.gitproxy.permission;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;

/**
 * Service that evaluates whether a proxy user is authorised to push to or approve a push for a given repository.
 *
 * <h3>Fail-closed semantics</h3>
 *
 * <p>If <em>any</em> permission rows exist for the {@code (provider, path)} combination the request is denied unless
 * the user appears in the matching set for the requested operation. If <em>no</em> rows match the path the request is
 * also denied — the permission store must explicitly enumerate every permitted user.
 *
 * <h3>Path matching</h3>
 *
 * <p>Paths use the {@code /owner/repo} convention (leading slash, no {@code .git} suffix). Matching is controlled by
 * {@link RepoPermission.PathType}:
 *
 * <ul>
 *   <li>{@code LITERAL} — exact string equality
 *   <li>{@code GLOB} — {@link java.nio.file.FileSystem#getPathMatcher} with {@code glob:} prefix; {@code *} matches one
 *       path segment, {@code **} matches any depth
 *   <li>{@code REGEX} — full Java regex matched against the full path string
 * </ul>
 */
@Slf4j
public class RepoPermissionService {

    private final RepoPermissionStore store;

    public RepoPermissionService(RepoPermissionStore store) {
        this.store = store;
    }

    /**
     * Returns {@code true} when {@code username} is authorised to push to {@code path} at {@code provider}.
     * Fail-closed: returns {@code false} if no grants exist for the path.
     */
    public boolean isAllowedToPush(String username, String provider, String path) {
        return isAllowed(username, provider, path, RepoPermission.Operations.PUSH);
    }

    /**
     * Returns {@code true} when {@code username} is authorised to approve a push for {@code path} at {@code provider}.
     * Fail-closed: returns {@code false} if no grants exist for the path.
     */
    public boolean isAllowedToApprove(String username, String provider, String path) {
        return isAllowed(username, provider, path, RepoPermission.Operations.APPROVE);
    }

    // ---- store delegation ----

    public void save(RepoPermission permission) {
        store.save(permission);
    }

    public void delete(String id) {
        store.delete(id);
    }

    public Optional<RepoPermission> findById(String id) {
        return store.findById(id);
    }

    public List<RepoPermission> findAll() {
        return store.findAll();
    }

    public List<RepoPermission> findByUsername(String username) {
        return store.findByUsername(username);
    }

    public List<RepoPermission> findByProvider(String provider) {
        return store.findByProvider(provider);
    }

    /**
     * Seeds permissions from config on startup. Clears all CONFIG-sourced rows and re-inserts to keep YAML
     * authoritative; DB-sourced rows are left untouched.
     */
    public void seedFromConfig(List<RepoPermission> permissions) {
        // Remove existing CONFIG rows so YAML changes take effect on restart.
        store.findAll().stream()
                .filter(p -> p.getSource() == RepoPermission.Source.CONFIG)
                .forEach(p -> store.delete(p.getId()));
        for (RepoPermission p : permissions) {
            store.save(p);
        }
        log.info("Seeded {} permission grant(s) from config", permissions.size());
    }

    // ---- internals ----

    private boolean isAllowed(String username, String provider, String path, RepoPermission.Operations op) {
        List<RepoPermission> forProvider = store.findByProvider(provider);

        List<RepoPermission> forPath =
                forProvider.stream().filter(p -> matchesPath(p, path)).toList();

        if (forPath.isEmpty()) {
            log.debug("No permission grants for {}/{} — DENY (fail-closed)", provider, path);
            return false;
        }

        boolean allowed = forPath.stream()
                .filter(p -> p.getOperations() == op || p.getOperations() == RepoPermission.Operations.ALL)
                .anyMatch(p -> username.equals(p.getUsername()));

        log.debug(
                "Permission check: user={} provider={} path={} op={} → {}",
                username,
                provider,
                path,
                op,
                allowed ? "ALLOW" : "DENY");
        return allowed;
    }

    private boolean matchesPath(RepoPermission perm, String path) {
        return switch (perm.getPathType()) {
            case LITERAL -> perm.getPath().equals(path);
            case GLOB -> matchesGlob(perm.getPath(), path);
            case REGEX -> matchesRegex(perm.getPath(), path);
        };
    }

    private boolean matchesGlob(String pattern, String value) {
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(Paths.get(value));
        } catch (Exception e) {
            log.warn("Invalid glob pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }

    private boolean matchesRegex(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).matches();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }
}
