package com.rbc.fogwall.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.db.model.ScmApiActionQuery;
import com.rbc.fogwall.db.model.ScmApiActionRecord;
import com.rbc.fogwall.db.model.ScmApiActionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Integration tests for {@link JdbcScmApiActionStore} backed by an H2 in-memory database. */
class JdbcScmApiActionStoreIntegrationTest {

    JdbcScmApiActionStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = DataSourceFactory.h2InMemory("scm-api-action-test-" + UUID.randomUUID());
        store = new JdbcScmApiActionStore(ds);
        store.initialize();
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertTrue(store.findById("nope").isEmpty());
    }

    @Test
    void save_thenFindById_roundTripsAllFields() {
        ScmApiActionRecord record = ScmApiActionRecord.builder()
                .provider("github")
                .scmUsername("octocat")
                .resolvedUser("alice")
                .repoOwner("acme")
                .repoName("widgets")
                .mutationField("createIssue")
                .nodeId("R_1")
                .nodeType("REPOSITORY")
                .status(ScmApiActionStatus.FORWARDED)
                .reason(null)
                .variablesJson("{\"input\":{\"repositoryId\":\"R_1\"}}")
                .userAgent("GitHub CLI 2.98.0")
                .clientType("GH_CLI")
                .clientVersion("2.98.0")
                .build();

        store.save(record);

        Optional<ScmApiActionRecord> found = store.findById(record.getId());
        assertTrue(found.isPresent());
        ScmApiActionRecord r = found.get();
        assertEquals(record.getId(), r.getId());
        assertEquals("github", r.getProvider());
        assertEquals("octocat", r.getScmUsername());
        assertEquals("alice", r.getResolvedUser());
        assertEquals("acme", r.getRepoOwner());
        assertEquals("widgets", r.getRepoName());
        assertEquals("createIssue", r.getMutationField());
        assertEquals("R_1", r.getNodeId());
        assertEquals("REPOSITORY", r.getNodeType());
        assertEquals(ScmApiActionStatus.FORWARDED, r.getStatus());
        assertNull(r.getReason());
        assertEquals("{\"input\":{\"repositoryId\":\"R_1\"}}", r.getVariablesJson());
        assertEquals("GitHub CLI 2.98.0", r.getUserAgent());
        assertEquals("GH_CLI", r.getClientType());
        assertEquals("2.98.0", r.getClientVersion());
    }

    @Test
    void save_deniedRecord_persistsReason() {
        ScmApiActionRecord record = ScmApiActionRecord.builder()
                .provider("github")
                .scmUsername("octocat")
                .mutationField("deleteRepository")
                .status(ScmApiActionStatus.DENIED)
                .reason("mutation not allowlisted")
                .build();

        store.save(record);

        ScmApiActionRecord found = store.findById(record.getId()).orElseThrow();
        assertEquals(ScmApiActionStatus.DENIED, found.getStatus());
        assertEquals("mutation not allowlisted", found.getReason());
        assertNull(found.getRepoOwner());
    }

    @Test
    void find_filtersByStatus() {
        store.save(record("acme", "widgets", ScmApiActionStatus.FORWARDED));
        store.save(record("acme", "gadgets", ScmApiActionStatus.DENIED));

        List<ScmApiActionRecord> denied = store.find(
                ScmApiActionQuery.builder().status(ScmApiActionStatus.DENIED).build());
        assertEquals(1, denied.size());
        assertEquals(ScmApiActionStatus.DENIED, denied.get(0).getStatus());
    }

    @Test
    void find_filtersByRepoOwnerAndName() {
        store.save(record("acme", "widgets", ScmApiActionStatus.FORWARDED));
        store.save(record("other", "widgets", ScmApiActionStatus.FORWARDED));

        List<ScmApiActionRecord> results = store.find(ScmApiActionQuery.builder()
                .repoOwner("acme")
                .repoName("widgets")
                .build());
        assertEquals(1, results.size());
        assertEquals("acme", results.get(0).getRepoOwner());
    }

    @Test
    void find_searchMatchesRepoOwnerOrName_caseInsensitive() {
        store.save(record("Acme", "Widgets", ScmApiActionStatus.FORWARDED));
        store.save(record("other", "gadgets", ScmApiActionStatus.FORWARDED));

        List<ScmApiActionRecord> results =
                store.find(ScmApiActionQuery.builder().search("acme").build());
        assertEquals(1, results.size());
        assertEquals("Acme", results.get(0).getRepoOwner());
    }

    @Test
    void find_respectsLimitAndNewestFirstOrdering() throws InterruptedException {
        ScmApiActionRecord first = record("acme", "one", ScmApiActionStatus.FORWARDED);
        store.save(first);
        Thread.sleep(5);
        ScmApiActionRecord second = record("acme", "two", ScmApiActionStatus.FORWARDED);
        store.save(second);

        List<ScmApiActionRecord> newestFirst =
                store.find(ScmApiActionQuery.builder().limit(1).build());
        assertEquals(1, newestFirst.size());
        assertEquals(second.getId(), newestFirst.get(0).getId());

        List<ScmApiActionRecord> oldestFirst = store.find(
                ScmApiActionQuery.builder().newestFirst(false).limit(1).build());
        assertEquals(first.getId(), oldestFirst.get(0).getId());
    }

    private static ScmApiActionRecord record(String owner, String name, ScmApiActionStatus status) {
        return ScmApiActionRecord.builder()
                .provider("github")
                .scmUsername("octocat")
                .resolvedUser("alice")
                .repoOwner(owner)
                .repoName(name)
                .mutationField("createIssue")
                .status(status)
                .build();
    }
}
