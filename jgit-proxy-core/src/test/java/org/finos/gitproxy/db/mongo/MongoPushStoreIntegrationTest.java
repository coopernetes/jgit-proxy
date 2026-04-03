package org.finos.gitproxy.db.mongo;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.finos.gitproxy.db.model.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
class MongoPushStoreIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    MongoPushStore store;

    @BeforeEach
    void setUp() {
        store = new MongoPushStore(
                MongoClients.create(MONGO.getConnectionString()),
                "testdb_" + UUID.randomUUID().toString().replace("-", ""));
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private PushRecord record(String id) {
        return PushRecord.builder()
                .id(id)
                .url("https://github.com/org/repo")
                .project("org")
                .repoName("repo")
                .branch("refs/heads/main")
                .commitFrom("abc")
                .commitTo("def")
                .message("feat: something")
                .author("Dev")
                .authorEmail("dev@example.com")
                .status(PushStatus.RECEIVED)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void saveAndFindById_returnsRecord() {
        String id = UUID.randomUUID().toString();
        store.save(record(id));

        var found = store.findById(id);
        assertTrue(found.isPresent());
        assertEquals(id, found.get().getId());
        assertEquals("repo", found.get().getRepoName());
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertTrue(store.findById(UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    void delete_removesRecord() {
        String id = UUID.randomUUID().toString();
        store.save(record(id));
        store.delete(id);
        assertTrue(store.findById(id).isEmpty());
    }

    @Test
    void delete_nonExistentId_doesNotThrow() {
        assertDoesNotThrow(() -> store.delete(UUID.randomUUID().toString()));
    }

    @Test
    void find_byStatus_returnsMatchingRecords() {
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        store.save(record(id1)); // RECEIVED
        PushRecord blocked = PushRecord.builder()
                .id(id2)
                .status(PushStatus.BLOCKED)
                .repoName("repo")
                .project("org")
                .branch("refs/heads/main")
                .timestamp(Instant.now())
                .build();
        store.save(blocked);

        var results = store.find(PushQuery.builder().status(PushStatus.RECEIVED).build());
        assertTrue(results.stream().anyMatch(r -> r.getId().equals(id1)));
        assertTrue(results.stream().noneMatch(r -> r.getId().equals(id2)));
    }

    @Test
    void find_byRepoName_returnsMatchingRecords() {
        String id = UUID.randomUUID().toString();
        store.save(record(id));

        var results = store.find(PushQuery.builder().repoName("repo").build());
        assertTrue(results.stream().anyMatch(r -> r.getId().equals(id)));
    }

    @Test
    void find_byBranch_returnsMatchingRecords() {
        String id = UUID.randomUUID().toString();
        store.save(record(id));

        var results = store.find(PushQuery.builder().branch("refs/heads/main").build());
        assertTrue(results.stream().anyMatch(r -> r.getId().equals(id)));
    }

    @Test
    void find_withLimit_returnsAtMostLimitRecords() {
        for (int i = 0; i < 5; i++) {
            store.save(record(UUID.randomUUID().toString()));
        }
        var results = store.find(PushQuery.builder().limit(3).build());
        assertTrue(results.size() <= 3);
    }

    @Test
    void find_emptyStore_returnsEmptyList() {
        var results = store.find(
                PushQuery.builder().repoName("nonexistent-" + UUID.randomUUID()).build());
        assertTrue(results.isEmpty());
    }

    @Test
    void approve_changesStatusToApproved() {
        String id = UUID.randomUUID().toString();
        store.save(record(id));

        Attestation att = Attestation.builder()
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("reviewer")
                .timestamp(Instant.now())
                .build();
        PushRecord updated = store.approve(id, att);

        assertEquals(PushStatus.APPROVED, updated.getStatus());
        assertNotNull(updated.getAttestation());
        assertEquals("reviewer", updated.getAttestation().getReviewerUsername());
    }

    @Test
    void reject_changesStatusToRejected() {
        String id = UUID.randomUUID().toString();
        store.save(record(id));

        Attestation att = Attestation.builder()
                .type(Attestation.Type.REJECTION)
                .reviewerUsername("reviewer")
                .reason("policy violation")
                .timestamp(Instant.now())
                .build();
        PushRecord updated = store.reject(id, att);

        assertEquals(PushStatus.REJECTED, updated.getStatus());
        assertEquals("policy violation", updated.getAttestation().getReason());
    }

    @Test
    void cancel_changesStatusToCanceled() {
        String id = UUID.randomUUID().toString();
        store.save(record(id));

        PushRecord updated = store.cancel(id, null);
        assertEquals(PushStatus.CANCELED, updated.getStatus());
    }

    @Test
    void approve_unknownId_throwsException() {
        Attestation att = Attestation.builder()
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("reviewer")
                .timestamp(Instant.now())
                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.approve(UUID.randomUUID().toString(), att));
    }

    @Test
    void save_withSteps_stepsRoundTripCorrectly() {
        String id = UUID.randomUUID().toString();
        PushRecord r = record(id);
        r.setSteps(List.of(PushStep.builder()
                .id(UUID.randomUUID().toString())
                .stepName("checkAuthor")
                .stepOrder(1000)
                .status(StepStatus.PASS)
                .logs(List.of("Author OK"))
                .timestamp(Instant.now())
                .build()));
        store.save(r);

        PushRecord found = store.findById(id).orElseThrow();
        assertNotNull(found.getSteps());
        assertEquals(1, found.getSteps().size());
        assertEquals("checkAuthor", found.getSteps().get(0).getStepName());
        assertEquals(StepStatus.PASS, found.getSteps().get(0).getStatus());
    }

    @Test
    void approve_attestationPersistedAndReadable() {
        String id = UUID.randomUUID().toString();
        store.save(record(id));

        Attestation att = Attestation.builder()
                .type(Attestation.Type.APPROVAL)
                .reviewerUsername("alice")
                .reviewerEmail("alice@example.com")
                .reason("looks good")
                .automated(false)
                .timestamp(Instant.now())
                .build();
        store.approve(id, att);

        PushRecord found = store.findById(id).orElseThrow();
        assertEquals("alice", found.getAttestation().getReviewerUsername());
        assertEquals("looks good", found.getAttestation().getReason());
    }

    @Test
    void multipleSaves_allVisible() {
        for (int i = 0; i < 5; i++) {
            store.save(record(UUID.randomUUID().toString()));
        }
        var results = store.find(PushQuery.builder().repoName("repo").build());
        assertTrue(results.size() >= 5);
    }

    @Test
    void initialize_calledTwice_doesNotThrow() {
        assertDoesNotThrow(() -> store.initialize());
    }
}
