package org.finos.gitproxy.db.mongo;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.util.List;
import java.util.UUID;
import org.finos.gitproxy.db.model.AccessRule;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
class MongoRepoRegistryIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    MongoRepoRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MongoRepoRegistry(
                MongoClients.create(MONGO.getConnectionString()),
                "testdb_" + UUID.randomUUID().toString().replace("-", ""));
        registry.initialize();
    }

    private AccessRule rule(String provider, String owner, String name) {
        return AccessRule.builder()
                .provider(provider)
                .owner(owner)
                .name(name)
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .enabled(true)
                .build();
    }

    @Test
    void save_andFindById_roundTripsAllFields() {
        AccessRule r = AccessRule.builder()
                .provider("github")
                .slug("org/repo")
                .owner("org")
                .name("repo")
                .access(AccessRule.Access.DENY)
                .operations(AccessRule.Operations.PUSH)
                .description("block pushes")
                .enabled(false)
                .ruleOrder(42)
                .source(AccessRule.Source.CONFIG)
                .build();
        registry.save(r);

        AccessRule found = registry.findById(r.getId()).orElseThrow();
        assertEquals(r.getId(), found.getId());
        assertEquals("github", found.getProvider());
        assertEquals("org/repo", found.getSlug());
        assertEquals("org", found.getOwner());
        assertEquals("repo", found.getName());
        assertEquals(AccessRule.Access.DENY, found.getAccess());
        assertEquals(AccessRule.Operations.PUSH, found.getOperations());
        assertEquals("block pushes", found.getDescription());
        assertFalse(found.isEnabled());
        assertEquals(42, found.getRuleOrder());
        assertEquals(AccessRule.Source.CONFIG, found.getSource());
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertTrue(registry.findById("no-such-id").isEmpty());
    }

    @Test
    void findAll_returnsSortedByRuleOrderThenId() {
        AccessRule high = AccessRule.builder()
                .id("aaa")
                .owner("org")
                .name("repo")
                .ruleOrder(10)
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .build();
        AccessRule low = AccessRule.builder()
                .id("zzz")
                .owner("org")
                .name("repo2")
                .ruleOrder(200)
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .build();
        registry.save(low);
        registry.save(high);

        List<AccessRule> all = registry.findAll();
        assertEquals(2, all.size());
        assertEquals("aaa", all.get(0).getId());
        assertEquals("zzz", all.get(1).getId());
    }

    @Test
    void update_replacesExistingDocument() {
        AccessRule r = rule("github", "org", "repo");
        registry.save(r);

        AccessRule updated = AccessRule.builder()
                .id(r.getId())
                .provider("gitlab")
                .owner("org")
                .name("repo")
                .access(AccessRule.Access.DENY)
                .operations(AccessRule.Operations.FETCH)
                .enabled(false)
                .build();
        registry.update(updated);

        AccessRule found = registry.findById(r.getId()).orElseThrow();
        assertEquals("gitlab", found.getProvider());
        assertEquals(AccessRule.Access.DENY, found.getAccess());
        assertFalse(found.isEnabled());
    }

    @Test
    void delete_removesDocument() {
        AccessRule r = rule("github", "org", "repo");
        registry.save(r);
        registry.delete(r.getId());
        assertTrue(registry.findById(r.getId()).isEmpty());
    }

    @Test
    void findEnabledForProvider_returnsMatchingAndNullProviderRules() {
        AccessRule githubRule = rule("github", "org", "gh-repo");
        AccessRule gitlabRule = rule("gitlab", "org", "gl-repo");
        AccessRule globalRule = AccessRule.builder()
                .owner("org")
                .name("global-repo")
                .access(AccessRule.Access.ALLOW)
                .operations(AccessRule.Operations.BOTH)
                .enabled(true)
                .build();
        AccessRule disabledRule = AccessRule.builder()
                .provider("github")
                .owner("org")
                .name("disabled-repo")
                .access(AccessRule.Access.DENY)
                .operations(AccessRule.Operations.BOTH)
                .enabled(false)
                .build();
        registry.save(githubRule);
        registry.save(gitlabRule);
        registry.save(globalRule);
        registry.save(disabledRule);

        List<AccessRule> results = registry.findEnabledForProvider("github");
        // Should include github-specific and global (null provider), but not gitlab or disabled
        assertTrue(results.stream().anyMatch(r -> "gh-repo".equals(r.getName())));
        assertTrue(results.stream().anyMatch(r -> "global-repo".equals(r.getName())));
        assertTrue(results.stream().noneMatch(r -> "gl-repo".equals(r.getName())));
        assertTrue(results.stream().noneMatch(r -> "disabled-repo".equals(r.getName())));
    }

    @Test
    void findEnabledForProvider_emptyStore_returnsEmpty() {
        assertTrue(registry.findEnabledForProvider("github").isEmpty());
    }
}
