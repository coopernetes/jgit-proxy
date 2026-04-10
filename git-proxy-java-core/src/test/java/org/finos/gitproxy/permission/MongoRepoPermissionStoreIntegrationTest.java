package org.finos.gitproxy.permission;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
class MongoRepoPermissionStoreIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    MongoRepoPermissionStore store;

    @BeforeEach
    void setUp() {
        store = new MongoRepoPermissionStore(
                MongoClients.create(MONGO.getConnectionString()),
                "testdb_" + UUID.randomUUID().toString().replace("-", ""));
        store.initialize();
    }

    private RepoPermission perm(String username, String provider, String path) {
        return RepoPermission.builder()
                .username(username)
                .provider(provider)
                .path(path)
                .build();
    }

    @Test
    void save_andFindById_roundTripsAllFields() {
        RepoPermission p = RepoPermission.builder()
                .username("alice")
                .provider("github")
                .path("/org/repo")
                .pathType(RepoPermission.PathType.GLOB)
                .operations(RepoPermission.Operations.PUSH)
                .source(RepoPermission.Source.CONFIG)
                .build();
        store.save(p);

        RepoPermission found = store.findById(p.getId()).orElseThrow();
        assertEquals(p.getId(), found.getId());
        assertEquals("alice", found.getUsername());
        assertEquals("github", found.getProvider());
        assertEquals("/org/repo", found.getPath());
        assertEquals(RepoPermission.PathType.GLOB, found.getPathType());
        assertEquals(RepoPermission.Operations.PUSH, found.getOperations());
        assertEquals(RepoPermission.Source.CONFIG, found.getSource());
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertTrue(store.findById("no-such-id").isEmpty());
    }

    @Test
    void findAll_returnsSortedByProviderPathUsername() {
        store.save(perm("bob", "github", "/org/z-repo"));
        store.save(perm("alice", "github", "/org/a-repo"));
        store.save(perm("carol", "gitlab", "/org/repo"));

        List<RepoPermission> all = store.findAll();
        assertEquals(3, all.size());
        // sorted by provider then path then username
        assertEquals("alice", all.get(0).getUsername());
        assertEquals("bob", all.get(1).getUsername());
        assertEquals("carol", all.get(2).getUsername());
    }

    @Test
    void delete_removesDocument() {
        RepoPermission p = perm("alice", "github", "/org/repo");
        store.save(p);
        store.delete(p.getId());
        assertTrue(store.findById(p.getId()).isEmpty());
    }

    @Test
    void findByUsername_returnsOnlyMatchingUser() {
        store.save(perm("alice", "github", "/org/repo-a"));
        store.save(perm("alice", "github", "/org/repo-b"));
        store.save(perm("bob", "github", "/org/repo-c"));

        List<RepoPermission> results = store.findByUsername("alice");
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(p -> "alice".equals(p.getUsername())));
    }

    @Test
    void findByProvider_returnsOnlyMatchingProvider() {
        store.save(perm("alice", "github", "/org/repo"));
        store.save(perm("bob", "gitlab", "/org/repo"));

        List<RepoPermission> results = store.findByProvider("github");
        assertEquals(1, results.size());
        assertEquals("github", results.get(0).getProvider());
    }

    @Test
    void findAll_emptyStore_returnsEmpty() {
        assertTrue(store.findAll().isEmpty());
    }
}
