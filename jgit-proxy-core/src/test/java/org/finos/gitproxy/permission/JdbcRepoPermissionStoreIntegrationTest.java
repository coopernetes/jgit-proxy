package org.finos.gitproxy.permission;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.finos.gitproxy.db.jdbc.DataSourceFactory;
import org.finos.gitproxy.db.jdbc.DatabaseMigrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link JdbcRepoPermissionStore} backed by an H2 in-memory database.
 *
 * <p>Each test gets its own isolated H2 database to prevent state leakage.
 */
class JdbcRepoPermissionStoreIntegrationTest {

    JdbcRepoPermissionStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = DataSourceFactory.h2InMemory("perm-test-" + UUID.randomUUID());
        DatabaseMigrator.migrate(ds);
        store = new JdbcRepoPermissionStore(ds);
    }

    private RepoPermission perm(String username, String provider, String path) {
        return RepoPermission.builder()
                .username(username)
                .provider(provider)
                .path(path)
                .pathType(RepoPermission.PathType.LITERAL)
                .operations(RepoPermission.Operations.ALL)
                .source(RepoPermission.Source.DB)
                .build();
    }

    // ---- save / findById ----

    @Test
    void save_findById_roundTrip() {
        RepoPermission p = perm("alice", "github", "/owner/repo");
        store.save(p);

        var found = store.findById(p.getId());
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().getUsername());
        assertEquals("github", found.get().getProvider());
        assertEquals("/owner/repo", found.get().getPath());
        assertEquals(RepoPermission.PathType.LITERAL, found.get().getPathType());
        assertEquals(RepoPermission.Operations.ALL, found.get().getOperations());
        assertEquals(RepoPermission.Source.DB, found.get().getSource());
    }

    @Test
    void findById_notFound_returnsEmpty() {
        assertTrue(store.findById("does-not-exist").isEmpty());
    }

    // ---- delete ----

    @Test
    void delete_removesRow() {
        RepoPermission p = perm("alice", "github", "/owner/repo");
        store.save(p);
        store.delete(p.getId());
        assertTrue(store.findById(p.getId()).isEmpty());
    }

    // ---- findAll ----

    @Test
    void findAll_returnsAllRows() {
        store.save(perm("alice", "github", "/owner/a"));
        store.save(perm("bob", "github", "/owner/b"));
        assertEquals(2, store.findAll().size());
    }

    // ---- findByUsername ----

    @Test
    void findByUsername_filtersCorrectly() {
        store.save(perm("alice", "github", "/owner/a"));
        store.save(perm("alice", "github", "/owner/b"));
        store.save(perm("bob", "github", "/owner/a"));

        List<RepoPermission> alicePerms = store.findByUsername("alice");
        assertEquals(2, alicePerms.size());
        assertTrue(alicePerms.stream().allMatch(p -> "alice".equals(p.getUsername())));
    }

    // ---- findByProvider ----

    @Test
    void findByProvider_filtersCorrectly() {
        store.save(perm("alice", "github", "/owner/a"));
        store.save(perm("bob", "gitlab", "/owner/b"));

        List<RepoPermission> githubPerms = store.findByProvider("github");
        assertEquals(1, githubPerms.size());
        assertEquals("alice", githubPerms.get(0).getUsername());

        List<RepoPermission> gitlabPerms = store.findByProvider("gitlab");
        assertEquals(1, gitlabPerms.size());
        assertEquals("bob", gitlabPerms.get(0).getUsername());
    }

    // ---- operations and path_type round-trip ----

    @Test
    void pushOnlyOperation_persistedAndRetrieved() {
        RepoPermission p = RepoPermission.builder()
                .username("alice")
                .provider("github")
                .path("/owner/repo")
                .pathType(RepoPermission.PathType.GLOB)
                .operations(RepoPermission.Operations.PUSH)
                .source(RepoPermission.Source.CONFIG)
                .build();
        store.save(p);

        var found = store.findById(p.getId()).orElseThrow();
        assertEquals(RepoPermission.Operations.PUSH, found.getOperations());
        assertEquals(RepoPermission.PathType.GLOB, found.getPathType());
        assertEquals(RepoPermission.Source.CONFIG, found.getSource());
    }
}
