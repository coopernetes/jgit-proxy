package org.finos.gitproxy.permission;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RepoPermissionService} using an in-memory store.
 *
 * <p>Covers fail-closed semantics, LITERAL/GLOB path matching, operation scoping, multi-user scenarios, and
 * {@code seedFromConfig}.
 */
class RepoPermissionServiceTest {

    RepoPermissionService svc;

    @BeforeEach
    void setUp() {
        svc = new RepoPermissionService(new InMemoryRepoPermissionStore());
    }

    private RepoPermission grant(String username, String provider, String path) {
        return RepoPermission.builder()
                .username(username)
                .provider(provider)
                .path(path)
                .pathType(RepoPermission.PathType.LITERAL)
                .operations(RepoPermission.Operations.ALL)
                .source(RepoPermission.Source.DB)
                .build();
    }

    private RepoPermission grant(
            String username,
            String provider,
            String path,
            RepoPermission.PathType pathType,
            RepoPermission.Operations ops) {
        return RepoPermission.builder()
                .username(username)
                .provider(provider)
                .path(path)
                .pathType(pathType)
                .operations(ops)
                .source(RepoPermission.Source.DB)
                .build();
    }

    // ---- fail-closed: no grants ----

    @Test
    void noGrants_push_denied() {
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    @Test
    void noGrants_approve_denied() {
        assertFalse(svc.isAllowedToApprove("alice", "github", "/owner/repo"));
    }

    // ---- literal match: user present ----

    @Test
    void literalGrant_correctUser_push_allowed() {
        svc.save(grant("alice", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    @Test
    void literalGrant_wrongUser_push_denied() {
        svc.save(grant("alice", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("bob", "github", "/owner/repo"));
    }

    // ---- fail-closed: path exists for provider but no user matches ----

    @Test
    void pathExistsButNoUserMatch_denied() {
        // Bob has access; Alice does not — deny Alice even though the path is managed
        svc.save(grant("bob", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    // ---- operation scoping ----

    @Test
    void pushOnlyGrant_allowsPush_deniesApprove() {
        svc.save(grant(
                "alice", "github", "/owner/repo", RepoPermission.PathType.LITERAL, RepoPermission.Operations.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToApprove("alice", "github", "/owner/repo"));
    }

    @Test
    void approveOnlyGrant_allowsApprove_deniesPush() {
        svc.save(grant(
                "alice", "github", "/owner/repo", RepoPermission.PathType.LITERAL, RepoPermission.Operations.APPROVE));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToApprove("alice", "github", "/owner/repo"));
    }

    @Test
    void allGrant_allowsBothOperations() {
        svc.save(grant(
                "alice", "github", "/owner/repo", RepoPermission.PathType.LITERAL, RepoPermission.Operations.ALL));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToApprove("alice", "github", "/owner/repo"));
    }

    // ---- provider isolation ----

    @Test
    void grantForDifferentProvider_doesNotAllow() {
        svc.save(grant("alice", "gitlab", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    // ---- glob matching ----

    @Test
    void globGrant_matchesAllReposUnderOwner_allowed() {
        svc.save(grant("alice", "github", "/owner/*", RepoPermission.PathType.GLOB, RepoPermission.Operations.ALL));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo-a"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo-b"));
    }

    @Test
    void globGrant_doesNotMatchOtherOwner() {
        svc.save(grant("alice", "github", "/owner/*", RepoPermission.PathType.GLOB, RepoPermission.Operations.ALL));
        assertFalse(svc.isAllowedToPush("alice", "github", "/other/repo"));
    }

    @Test
    void globGrant_doubleWildcard_matchesAnyPath() {
        svc.save(grant("alice", "github", "/**", RepoPermission.PathType.GLOB, RepoPermission.Operations.ALL));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/other/thing"));
    }

    // ---- regex matching ----

    @Test
    void regexGrant_matchesPattern() {
        svc.save(grant(
                "alice",
                "github",
                "/coopernetes/test-repo-.*",
                RepoPermission.PathType.REGEX,
                RepoPermission.Operations.ALL));
        assertTrue(svc.isAllowedToPush("alice", "github", "/coopernetes/test-repo-codeberg"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/coopernetes/test-repo-gitlab"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/coopernetes/test-repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/other/test-repo-codeberg"));
    }

    @Test
    void regexGrant_invalidPattern_treatedAsNoMatch() {
        svc.save(grant("alice", "github", "[invalid", RepoPermission.PathType.REGEX, RepoPermission.Operations.ALL));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    // ---- seedFromConfig ----

    @Test
    void seedFromConfig_replacesConfigRows_keepsDbRows() {
        // DB-sourced row — should survive reseed
        RepoPermission dbRow = RepoPermission.builder()
                .username("bob")
                .provider("github")
                .path("/owner/repo")
                .operations(RepoPermission.Operations.ALL)
                .source(RepoPermission.Source.DB)
                .build();
        svc.save(dbRow);

        // Seed with a config row for alice
        RepoPermission configRow = RepoPermission.builder()
                .username("alice")
                .provider("github")
                .path("/owner/repo")
                .operations(RepoPermission.Operations.ALL)
                .source(RepoPermission.Source.CONFIG)
                .build();
        svc.seedFromConfig(List.of(configRow));

        // Bob (DB) still allowed; alice (CONFIG) also allowed
        assertTrue(svc.isAllowedToPush("bob", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));

        // Re-seed without alice — alice should be removed, bob stays
        svc.seedFromConfig(List.of());
        assertTrue(svc.isAllowedToPush("bob", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    // ---- CRUD delegation ----

    @Test
    void save_findById_delete() {
        RepoPermission p = grant("alice", "github", "/owner/repo");
        svc.save(p);

        assertTrue(svc.findById(p.getId()).isPresent());
        assertEquals("alice", svc.findById(p.getId()).get().getUsername());

        svc.delete(p.getId());
        assertTrue(svc.findById(p.getId()).isEmpty());
    }

    @Test
    void findByUsername_returnsOnlyMatchingRows() {
        svc.save(grant("alice", "github", "/owner/a"));
        svc.save(grant("alice", "github", "/owner/b"));
        svc.save(grant("bob", "github", "/owner/a"));

        List<RepoPermission> alicePerms = svc.findByUsername("alice");
        assertEquals(2, alicePerms.size());
        assertTrue(alicePerms.stream().allMatch(p -> "alice".equals(p.getUsername())));
    }

    @Test
    void findByProvider_returnsOnlyMatchingRows() {
        svc.save(grant("alice", "github", "/owner/a"));
        svc.save(grant("bob", "gitlab", "/owner/b"));

        List<RepoPermission> githubPerms = svc.findByProvider("github");
        assertEquals(1, githubPerms.size());
        assertEquals("alice", githubPerms.get(0).getUsername());
    }
}
