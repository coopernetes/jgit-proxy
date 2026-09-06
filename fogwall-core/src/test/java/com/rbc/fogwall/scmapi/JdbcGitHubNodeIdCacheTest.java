package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.db.jdbc.DataSourceFactory;
import com.rbc.fogwall.db.jdbc.JdbcPushStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Integration tests for {@link JdbcGitHubNodeIdCache} backed by an H2 in-memory database. */
class JdbcGitHubNodeIdCacheTest {

    JdbcGitHubNodeIdCache cache;

    @BeforeEach
    void setUp() {
        DataSource ds = DataSourceFactory.h2InMemory("node-id-cache-test-" + UUID.randomUUID());
        new JdbcPushStore(ds).initialize();
        cache = new JdbcGitHubNodeIdCache(ds, Duration.ofMinutes(5));
    }

    @Test
    void lookup_emptyCache_returnsEmpty() {
        assertTrue(cache.lookup("github", "R_1").isEmpty());
    }

    @Test
    void store_thenLookup_returnsCachedOwnerRepo() {
        cache.store("github", "R_1", new OwnerRepo("acme", "widgets"));

        Optional<OwnerRepo> result = cache.lookup("github", "R_1");
        assertTrue(result.isPresent());
        assertEquals(new OwnerRepo("acme", "widgets"), result.get());
    }

    @Test
    void lookup_differentProvider_returnsEmpty() {
        cache.store("github", "R_1", new OwnerRepo("acme", "widgets"));
        assertTrue(cache.lookup("gitlab", "R_1").isEmpty());
    }

    @Test
    void lookup_differentNodeId_returnsEmpty() {
        cache.store("github", "R_1", new OwnerRepo("acme", "widgets"));
        assertTrue(cache.lookup("github", "R_2").isEmpty());
    }

    @Test
    void store_expiredEntry_returnsEmpty() {
        DataSource ds = DataSourceFactory.h2InMemory("node-id-cache-expired-" + UUID.randomUUID());
        new JdbcPushStore(ds).initialize();
        JdbcGitHubNodeIdCache expiredCache = new JdbcGitHubNodeIdCache(ds, Duration.ZERO);
        expiredCache.store("github", "R_1", new OwnerRepo("acme", "widgets"));

        assertTrue(expiredCache.lookup("github", "R_1").isEmpty());
    }

    @Test
    void store_overwritesExistingEntry() {
        cache.store("github", "R_1", new OwnerRepo("acme", "widgets"));
        cache.store("github", "R_1", new OwnerRepo("acme", "renamed-widgets"));

        Optional<OwnerRepo> result = cache.lookup("github", "R_1");
        assertTrue(result.isPresent());
        assertEquals("renamed-widgets", result.get().name());
    }
}
