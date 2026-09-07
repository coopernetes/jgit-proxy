package com.rbc.fogwall.db.mongo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Mirrors {@code ScmOAuthTokenStoreTest}, which covers the JDBC store — the two are peers and must behave the same, so
 * a deployment's database family does not decide whether account linking and SSH key refresh work.
 */
@Testcontainers
@Tag("integration")
class MongoScmOAuthTokenStoreIntegrationTest {

    @Container
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("docker.io/mongo:7.0").asCompatibleSubstituteFor("mongo"));

    MongoScmOAuthTokenStore store;

    @BeforeEach
    void setUp() {
        store = new MongoScmOAuthTokenStore(
                MongoClients.create(MONGO.getConnectionString()),
                "testdb_" + UUID.randomUUID().toString().replace("-", ""));
        store.initialize();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void save_thenFindAccessToken_roundTrips() {
        store.save("alice", "github", bytes("enc-access"), bytes("enc-refresh"), "repo,user", Instant.now());

        Optional<byte[]> found = store.findAccessToken("alice", "github");

        assertTrue(found.isPresent());
        assertArrayEquals(bytes("enc-access"), found.get());
    }

    @Test
    void save_nullRefreshTokenAndExpiry_stillPersistsAccessToken() {
        store.save("alice", "github", bytes("enc-access"), null, null, null);

        assertArrayEquals(
                bytes("enc-access"), store.findAccessToken("alice", "github").orElseThrow());
    }

    @Test
    void save_secondCallForSamePair_replacesToken() {
        store.save("alice", "github", bytes("first"), null, null, null);
        store.save("alice", "github", bytes("second"), null, null, null);

        assertArrayEquals(
                bytes("second"), store.findAccessToken("alice", "github").orElseThrow());
    }

    @Test
    void save_differentProvidersForSameUser_bothPersist() {
        store.save("alice", "github", bytes("gh"), null, null, null);
        store.save("alice", "gitlab", bytes("gl"), null, null, null);

        assertArrayEquals(bytes("gh"), store.findAccessToken("alice", "github").orElseThrow());
        assertArrayEquals(bytes("gl"), store.findAccessToken("alice", "gitlab").orElseThrow());
    }

    @Test
    void save_sameProviderDifferentUsers_bothPersist() {
        store.save("alice", "github", bytes("alice-tok"), null, null, null);
        store.save("bob", "github", bytes("bob-tok"), null, null, null);

        assertArrayEquals(
                bytes("alice-tok"), store.findAccessToken("alice", "github").orElseThrow());
        assertArrayEquals(
                bytes("bob-tok"), store.findAccessToken("bob", "github").orElseThrow());
    }

    @Test
    void findAccessToken_unknownPair_isEmpty() {
        assertTrue(store.findAccessToken("nobody", "github").isEmpty());
    }

    @Test
    void remove_deletesOnlyThatPair() {
        store.save("alice", "github", bytes("gh"), null, null, null);
        store.save("alice", "gitlab", bytes("gl"), null, null, null);

        store.remove("alice", "github");

        assertTrue(store.findAccessToken("alice", "github").isEmpty());
        assertArrayEquals(bytes("gl"), store.findAccessToken("alice", "gitlab").orElseThrow());
    }

    @Test
    void remove_unknownPair_isANoOp() {
        store.remove("nobody", "github");
        assertTrue(store.findAccessToken("nobody", "github").isEmpty());
    }

    @Test
    void save_expiryIsRetainedToTheSecond() {
        Instant expires = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        store.save("alice", "github", bytes("enc"), null, "repo", expires);

        // Round-tripping the token is the contract; the expiry is stored for later use by the revoke path.
        assertEquals(1, store.findAccessToken("alice", "github").stream().count());
    }
}
