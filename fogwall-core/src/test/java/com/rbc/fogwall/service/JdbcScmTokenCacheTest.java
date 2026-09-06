package com.rbc.fogwall.service;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.db.jdbc.DataSourceFactory;
import com.rbc.fogwall.db.jdbc.JdbcPushStore;
import com.rbc.fogwall.user.JdbcUserStore;
import com.rbc.fogwall.user.UserEntry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
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
        Optional<CachedScmIdentity> result = cache.lookup("github", "hash-abc");
        assertTrue(result.isEmpty());
    }

    @Test
    void store_thenLookup_returnsCachedIdentity() {
        cache.store("github", "hash-abc", new CachedScmIdentity("alice", "alice-on-github"));

        Optional<CachedScmIdentity> result = cache.lookup("github", "hash-abc");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().proxyUsername());
        assertEquals("alice-on-github", result.get().scmLogin(), "the account the token names is cached too");
    }

    @Test
    void lookup_differentProvider_returnsEmpty() {
        cache.store("github", "hash-abc", new CachedScmIdentity("alice", "alice-on-github"));

        Optional<CachedScmIdentity> result = cache.lookup("gitlab", "hash-abc");
        assertTrue(result.isEmpty());
    }

    @Test
    void lookup_differentHash_returnsEmpty() {
        cache.store("github", "hash-abc", new CachedScmIdentity("alice", "alice-on-github"));

        Optional<CachedScmIdentity> result = cache.lookup("github", "hash-xyz");
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
        expiredCache.store("github", "hash-abc", new CachedScmIdentity("alice", "alice-on-github"));

        Optional<CachedScmIdentity> result = expiredCache.lookup("github", "hash-abc");
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

        cache.store("github", "hash-abc", new CachedScmIdentity("alice", "alice-on-github"));
        cache.store("github", "hash-abc", new CachedScmIdentity("bob", "bob-on-github"));

        Optional<CachedScmIdentity> result = cache.lookup("github", "hash-abc");
        assertTrue(result.isPresent());
        assertEquals("bob", result.get().proxyUsername());
        assertEquals("bob-on-github", result.get().scmLogin());
    }

    /** Rows written before scm_login existed keep resolving; they carry a null login and age out on the TTL. */
    @Test
    void entryWithoutALoginIsStillAHit() {
        cache.store("github", "hash-abc", new CachedScmIdentity("alice", null));

        Optional<CachedScmIdentity> result = cache.lookup("github", "hash-abc");
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().proxyUsername());
        assertNull(result.get().scmLogin());
    }
}
