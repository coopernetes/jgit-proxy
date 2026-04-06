package org.finos.gitproxy.db.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Exercises builder defaults and enum values for model classes in {@code db.model}. These are Lombok-generated — the
 * tests ensure all fields/enums are referenced so JaCoCo line coverage stays above the 95% threshold.
 */
class DbModelTest {

    // ---- AccessRule ----

    @Test
    void accessRule_defaults() {
        AccessRule rule = AccessRule.builder().build();

        assertNotNull(rule.getId());
        assertEquals(AccessRule.Access.ALLOW, rule.getAccess());
        assertEquals(AccessRule.Operations.ALL, rule.getOperations());
        assertTrue(rule.isEnabled());
        assertEquals(100, rule.getRuleOrder());
        assertEquals(AccessRule.Source.DB, rule.getSource());
        assertNull(rule.getProvider());
        assertNull(rule.getSlug());
        assertNull(rule.getOwner());
        assertNull(rule.getName());
        assertNull(rule.getDescription());
    }

    @Test
    void accessRule_builderOverridesDefaults() {
        AccessRule rule = AccessRule.builder()
                .id("fixed-id")
                .provider("github")
                .slug("org/*")
                .owner("org")
                .name("repo")
                .access(AccessRule.Access.DENY)
                .operations(AccessRule.Operations.FETCH)
                .description("test rule")
                .enabled(false)
                .ruleOrder(10)
                .source(AccessRule.Source.CONFIG)
                .build();

        assertEquals("fixed-id", rule.getId());
        assertEquals("github", rule.getProvider());
        assertEquals("org/*", rule.getSlug());
        assertEquals("org", rule.getOwner());
        assertEquals("repo", rule.getName());
        assertEquals(AccessRule.Access.DENY, rule.getAccess());
        assertEquals(AccessRule.Operations.FETCH, rule.getOperations());
        assertEquals("test rule", rule.getDescription());
        assertFalse(rule.isEnabled());
        assertEquals(10, rule.getRuleOrder());
        assertEquals(AccessRule.Source.CONFIG, rule.getSource());
    }

    @Test
    void accessRule_allAccessEnumValues() {
        assertNotNull(AccessRule.Access.valueOf("ALLOW"));
        assertNotNull(AccessRule.Access.valueOf("DENY"));
    }

    @Test
    void accessRule_allOperationsEnumValues() {
        assertNotNull(AccessRule.Operations.valueOf("FETCH"));
        assertNotNull(AccessRule.Operations.valueOf("PUSH"));
        assertNotNull(AccessRule.Operations.valueOf("ALL"));
    }

    @Test
    void accessRule_allSourceEnumValues() {
        assertNotNull(AccessRule.Source.valueOf("CONFIG"));
        assertNotNull(AccessRule.Source.valueOf("DB"));
    }

    @Test
    void accessRule_equalsAndHashCode() {
        AccessRule a = AccessRule.builder().id("id-1").build();
        AccessRule b = AccessRule.builder().id("id-1").build();
        // Lombok @Data generates equals/hashCode on all fields; same id + same defaults are equal
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void accessRule_toString_containsId() {
        AccessRule rule = AccessRule.builder().id("my-rule").build();
        assertTrue(rule.toString().contains("my-rule"));
    }

    // ---- FetchRecord ----

    @Test
    void fetchRecord_defaults() {
        FetchRecord record =
                FetchRecord.builder().result(FetchRecord.Result.ALLOWED).build();

        assertNotNull(record.getId());
        assertNotNull(record.getTimestamp());
        assertNull(record.getProvider());
        assertNull(record.getOwner());
        assertNull(record.getRepoName());
        assertNull(record.getPushUsername());
        assertNull(record.getResolvedUser());
    }

    @Test
    void fetchRecord_builderOverridesDefaults() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        FetchRecord record = FetchRecord.builder()
                .id("fetch-1")
                .timestamp(ts)
                .provider("github")
                .owner("myorg")
                .repoName("myrepo")
                .result(FetchRecord.Result.BLOCKED)
                .pushUsername("me")
                .resolvedUser("alice")
                .build();

        assertEquals("fetch-1", record.getId());
        assertEquals(ts, record.getTimestamp());
        assertEquals("github", record.getProvider());
        assertEquals("myorg", record.getOwner());
        assertEquals("myrepo", record.getRepoName());
        assertEquals(FetchRecord.Result.BLOCKED, record.getResult());
        assertEquals("me", record.getPushUsername());
        assertEquals("alice", record.getResolvedUser());
    }

    @Test
    void fetchRecord_allResultEnumValues() {
        assertNotNull(FetchRecord.Result.valueOf("ALLOWED"));
        assertNotNull(FetchRecord.Result.valueOf("BLOCKED"));
    }

    @Test
    void fetchRecord_equalsAndHashCode() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        FetchRecord a = FetchRecord.builder()
                .id("f1")
                .timestamp(ts)
                .result(FetchRecord.Result.ALLOWED)
                .build();
        FetchRecord b = FetchRecord.builder()
                .id("f1")
                .timestamp(ts)
                .result(FetchRecord.Result.ALLOWED)
                .build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void fetchRecord_toString_containsId() {
        FetchRecord record = FetchRecord.builder()
                .id("my-fetch")
                .result(FetchRecord.Result.ALLOWED)
                .build();
        assertTrue(record.toString().contains("my-fetch"));
    }
}
