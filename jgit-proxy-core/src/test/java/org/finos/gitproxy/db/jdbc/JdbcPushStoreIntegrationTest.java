package org.finos.gitproxy.db.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link JdbcPushStore} backed by an H2 in-memory database.
 *
 * <p>Each test gets its own isolated H2 database (unique name) to prevent state leakage between tests.
 *
 * <p>Covers the same contract as {@code InMemoryPushStoreTest} but exercises the full SQL path: schema initialization,
 * INSERT, SELECT, UPDATE, DELETE, and relationship tables (steps, commits, attestations).
 */
class JdbcPushStoreIntegrationTest {

    PushStore store;

    @BeforeEach
    void setUp() {
        // Unique DB name per test so H2 in-memory instances are fully isolated
        store = PushStoreFactory.h2InMemory("test-" + UUID.randomUUID());
    }

    // ---- helpers ----

    private static PushRecord record(String commitTo, String branch, String repoName) {
        return PushRecord.builder()
                .commitTo(commitTo)
                .branch(branch)
                .repoName(repoName)
                .user("dev")
                .authorEmail("dev@example.com")
                .build();
    }

    private static Attestation approvalFor(String pushId) {
        return Attestation.builder()
                .pushId(pushId)
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("reviewer")
                .reason("LGTM")
                .build();
    }

    // ---- save / findById ----

    @Test
    void saveAndFindById_returnsRecord() {
        PushRecord r = record("abc123", "refs/heads/main", "my-repo");
        store.save(r);

        Optional<PushRecord> found = store.findById(r.getId());

        assertTrue(found.isPresent());
        assertEquals(r.getId(), found.get().getId());
        assertEquals("abc123", found.get().getCommitTo());
        assertEquals("refs/heads/main", found.get().getBranch());
        assertEquals("my-repo", found.get().getRepoName());
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertTrue(store.findById("no-such-id").isEmpty());
    }

    // The JDBC store is append-only — each save() INSERTs a new event record.
    // Status transitions (approve/reject/cancel) use UPDATE, not save().
    // There is no upsert/overwrite contract for JDBC unlike InMemoryPushStore.

    // ---- delete ----

    @Test
    void delete_removesRecord() {
        PushRecord r = record("abc", "refs/heads/main", "repo");
        store.save(r);
        store.delete(r.getId());

        assertTrue(store.findById(r.getId()).isEmpty());
    }

    @Test
    void delete_nonExistentId_doesNotThrow() {
        assertDoesNotThrow(() -> store.delete("does-not-exist"));
    }

    // ---- find with query ----

    @Test
    void find_byStatus_returnsMatchingRecords() {
        PushRecord pending = record("a", "refs/heads/main", "repo");
        PushRecord approved = record("b", "refs/heads/main", "repo");
        store.save(pending);
        store.save(approved);
        store.approve(approved.getId(), approvalFor(approved.getId()));

        List<PushRecord> results =
                store.find(PushQuery.builder().status(PushStatus.APPROVED).build());

        assertEquals(1, results.size());
        assertEquals(approved.getId(), results.get(0).getId());
    }

    @Test
    void find_byRepoName_returnsMatchingRecords() {
        store.save(record("a", "refs/heads/main", "repoA"));
        store.save(record("b", "refs/heads/main", "repoB"));

        List<PushRecord> results =
                store.find(PushQuery.builder().repoName("repoA").build());

        assertEquals(1, results.size());
        assertEquals("repoA", results.get(0).getRepoName());
    }

    @Test
    void find_byBranch_returnsMatchingRecords() {
        store.save(record("a", "refs/heads/feature", "repo"));
        store.save(record("b", "refs/heads/main", "repo"));

        List<PushRecord> results =
                store.find(PushQuery.builder().branch("refs/heads/feature").build());

        assertEquals(1, results.size());
        assertEquals("refs/heads/feature", results.get(0).getBranch());
    }

    @Test
    void find_byCommitTo_returnsMatchingRecord() {
        store.save(record("commitXYZ", "refs/heads/main", "repo"));
        store.save(record("commitABC", "refs/heads/main", "repo"));

        List<PushRecord> results =
                store.find(PushQuery.builder().commitTo("commitXYZ").build());

        assertEquals(1, results.size());
        assertEquals("commitXYZ", results.get(0).getCommitTo());
    }

    @Test
    void find_withLimit_returnsAtMostLimitRecords() {
        for (int i = 0; i < 10; i++) {
            store.save(record("commit" + i, "refs/heads/main", "repo"));
        }

        List<PushRecord> results = store.find(PushQuery.builder().limit(3).build());

        assertEquals(3, results.size());
    }

    @Test
    void find_emptyStore_returnsEmptyList() {
        assertTrue(store.find(PushQuery.builder().build()).isEmpty());
    }

    // ---- approve / reject / cancel ----

    @Test
    void approve_changesStatusToApproved() {
        PushRecord r = record("abc", "refs/heads/main", "repo");
        store.save(r);

        PushRecord updated = store.approve(r.getId(), approvalFor(r.getId()));

        assertEquals(PushStatus.APPROVED, updated.getStatus());
        assertEquals(PushStatus.APPROVED, store.findById(r.getId()).get().getStatus());
    }

    @Test
    void reject_changesStatusToRejected() {
        PushRecord r = record("abc", "refs/heads/main", "repo");
        store.save(r);

        Attestation rejection = Attestation.builder()
                .pushId(r.getId())
                .type(Attestation.Type.REJECTION)
                .reviewerUsername("reviewer")
                .reason("Policy violation")
                .build();
        PushRecord updated = store.reject(r.getId(), rejection);

        assertEquals(PushStatus.REJECTED, updated.getStatus());
    }

    @Test
    void cancel_changesStatusToCanceled() {
        PushRecord r = record("abc", "refs/heads/main", "repo");
        store.save(r);

        Attestation cancellation = Attestation.builder()
                .pushId(r.getId())
                .type(Attestation.Type.CANCELLATION)
                .reviewerUsername("dev")
                .build();
        PushRecord updated = store.cancel(r.getId(), cancellation);

        assertEquals(PushStatus.CANCELED, updated.getStatus());
    }

    @Test
    void approve_unknownId_throwsException() {
        assertThrows(Exception.class, () -> store.approve("not-a-real-id", approvalFor("not-a-real-id")));
    }

    // ---- steps persistence ----

    @Test
    void save_withSteps_stepsRoundTripCorrectly() {
        PushRecord r = record("abc", "refs/heads/main", "repo");
        PushStep pass = PushStep.builder()
                .pushId(r.getId())
                .stepName("checkAuthorEmails")
                .stepOrder(2100)
                .status(StepStatus.PASS)
                .build();
        PushStep fail = PushStep.builder()
                .pushId(r.getId())
                .stepName("checkCommitMessages")
                .stepOrder(2200)
                .status(StepStatus.FAIL)
                .errorMessage("contains WIP")
                .content("blocked term: \"WIP\"")
                .build();
        r.setSteps(List.of(pass, fail));
        store.save(r);

        PushRecord loaded = store.findById(r.getId()).orElseThrow();

        assertEquals(2, loaded.getSteps().size());
        PushStep loadedPass = loaded.getSteps().stream()
                .filter(s -> s.getStepName().equals("checkAuthorEmails"))
                .findFirst()
                .orElseThrow();
        assertEquals(StepStatus.PASS, loadedPass.getStatus());

        PushStep loadedFail = loaded.getSteps().stream()
                .filter(s -> s.getStepName().equals("checkCommitMessages"))
                .findFirst()
                .orElseThrow();
        assertEquals(StepStatus.FAIL, loadedFail.getStatus());
        assertEquals("contains WIP", loadedFail.getErrorMessage());
    }

    // ---- attestation persistence ----

    @Test
    void approve_attestationPersistedAndReadable() {
        PushRecord r = record("abc", "refs/heads/main", "repo");
        store.save(r);

        Attestation att = Attestation.builder()
                .pushId(r.getId())
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("alice")
                .reason("Reviewed and approved")
                .build();
        store.approve(r.getId(), att);

        PushRecord loaded = store.findById(r.getId()).orElseThrow();
        assertNotNull(loaded.getAttestation(), "attestation should be persisted");
        assertEquals("alice", loaded.getAttestation().getReviewerUsername());
        assertEquals(Attestation.Type.APPROVAL, loaded.getAttestation().getType());
    }

    // ---- multiple saves ----

    @Test
    void multipleSaves_allVisible() {
        for (int i = 0; i < 20; i++) {
            store.save(record("commit" + i, "refs/heads/main", "repo"));
        }

        List<PushRecord> all = store.find(PushQuery.builder().limit(100).build());

        assertEquals(20, all.size());
    }

    // ---- initialize idempotency ----

    @Test
    void initialize_calledTwice_doesNotThrow() {
        assertDoesNotThrow(() -> store.initialize());
    }
}
