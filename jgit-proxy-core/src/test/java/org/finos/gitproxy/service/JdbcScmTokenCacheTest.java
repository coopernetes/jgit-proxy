package org.finos.gitproxy.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.finos.gitproxy.db.jdbc.DataSourceFactory;
import org.finos.gitproxy.db.jdbc.JdbcPushStore;
import org.finos.gitproxy.user.JdbcUserStore;
import org.finos.gitproxy.user.UserEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link JdbcScmTokenCache} backed by an H2 in-memory database.
 *
 * <p>Each test gets its own isolated H2 database. The scm_token_cache table has a FK on proxy_users, so a user row is
 * inserted before each test.
 */
class JdbcScmTokenCacheTest {

    JdbcScmTokenCache cache;
    JdbcUserStore userStore;

    @BeforeEach
    void setUp() {
        DataSource ds = DataSourceFactory.h2InMemory("cache-test-" + UUID.randomUUID());
        JdbcPushStore pushStore = new JdbcPushStore(ds);
        pushStore.initialize();
        cache = new JdbcScmTokenCache(ds, Duration.ofMinutes(30));
        userStore = new JdbcUserStore(ds, cache);

        userStore.upsertAll(List.of(UserEntry.builder()
                .username("alice")
                .passwordHash("{noop}pw")
                .emails(List.of())
                .scmIdentities(List.of())
                .build()));
    }

    @Test
    void lookup_emptyCache_returnsEmpty() {
        Optional<String> result = cache.lookup("github", "hash-abc");
        assertTrue(result.isEmpty());
    }

    @Test
    void store_thenLookup_returnsCachedUsername() {
        cache.store("github", "hash-abc", "alice");

        Optional<String> result = cache.lookup("github", "hash-abc");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get());
    }

    @Test
    void lookup_differentProvider_returnsEmpty() {
        cache.store("github", "hash-abc", "alice");

        Optional<String> result = cache.lookup("gitlab", "hash-abc");
        assertTrue(result.isEmpty());
    }

    @Test
    void lookup_differentHash_returnsEmpty() {
        cache.store("github", "hash-abc", "alice");

        Optional<String> result = cache.lookup("github", "hash-xyz");
        assertTrue(result.isEmpty());
    }

    @Test
    void store_expiredEntry_returnsEmpty() {
        // Cache with zero max-age so all entries are immediately expired
        DataSource ds = DataSourceFactory.h2InMemory("cache-expired-" + UUID.randomUUID());
        new JdbcPushStore(ds).initialize();
        JdbcScmTokenCache expiredCache = new JdbcScmTokenCache(ds, Duration.ZERO);
        new JdbcUserStore(ds, expiredCache)
                .upsertAll(List.of(UserEntry.builder()
                        .username("alice")
                        .passwordHash("{noop}pw")
                        .emails(List.of())
                        .scmIdentities(List.of())
                        .build()));
        expiredCache.store("github", "hash-abc", "alice");

        Optional<String> result = expiredCache.lookup("github", "hash-abc");
        assertTrue(result.isEmpty());
    }

    @Test
    void store_overwritesExistingEntry() {
        userStore.upsertAll(List.of(UserEntry.builder()
                .username("bob")
                .passwordHash("{noop}pw")
                .emails(List.of())
                .scmIdentities(List.of())
                .build()));

        cache.store("github", "hash-abc", "alice");
        cache.store("github", "hash-abc", "bob");

        Optional<String> result = cache.lookup("github", "hash-abc");
        assertTrue(result.isPresent());
        assertEquals("bob", result.get());
    }
}
