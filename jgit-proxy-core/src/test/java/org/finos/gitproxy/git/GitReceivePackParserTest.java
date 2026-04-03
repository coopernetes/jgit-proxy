package org.finos.gitproxy.git;

import static org.finos.gitproxy.git.GitClientUtils.ZERO_OID;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GitReceivePackParserTest {

    // Push 1 constants
    private static final String PUSH1_OLD = "61a0b5dd65652ed278b2f569c1ce5dea0e02ce61";
    private static final String PUSH1_NEW = "3348d03785fdeb43cc0b72077e9d2d7512c01a72";
    private static final String PUSH1_REF = "refs/heads/main";

    // Push 2 constants
    private static final String PUSH2_OLD = "3348d03785fdeb43cc0b72077e9d2d7512c01a72";
    private static final String PUSH2_NEW = "5b8690554d2ddd65f28466b829fc9b6879e2ba2d";

    // ---- Resource helpers ----

    private String loadPacketLine(String resourceName) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(is, "Resource not found: " + resourceName);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private byte[] loadBody(String resourceName) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(is, "Resource not found: " + resourceName);
            return is.readAllBytes();
        }
    }

    // ---- parsePush — sample 01 ----

    @Test
    void parsePush_sample01_extractsOldSha() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertEquals(PUSH1_OLD, info.getOldCommit());
    }

    @Test
    void parsePush_sample01_extractsNewSha() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertEquals(PUSH1_NEW, info.getNewCommit());
    }

    @Test
    void parsePush_sample01_extractsReference() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertEquals(PUSH1_REF, info.getReference());
    }

    @Test
    void parsePush_sample01_referenceHasNoNullBytes() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertFalse(info.getReference().contains("\u0000"), "Reference must not contain null bytes");
    }

    @Test
    void parsePush_sample01_referenceHasNoCapabilityString() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        // Capability strings appear after a NUL; after stripping NUL the reference should be a plain ref path
        assertFalse(info.getReference().contains("side-band"), "Reference must not contain capability strings");
        assertFalse(info.getReference().contains("ofs-delta"), "Reference must not contain capability strings");
    }

    @Test
    void parsePush_sample01_commitShaMatchesNewSha() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertNotNull(info.getCommit(), "Commit must not be null");
        assertEquals(
                PUSH1_NEW,
                info.getCommit().getSha(),
                "Commit SHA must be taken from the packet line (newCommit), not raw pack data");
    }

    @Test
    void parsePush_sample01_commitHasNonNullAuthor() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertNotNull(info.getCommit());
        assertNotNull(info.getCommit().getAuthor(), "Author must not be null");
    }

    @Test
    void parsePush_sample01_commitAuthorEmailNonEmpty() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertNotNull(info.getCommit());
        assertNotNull(info.getCommit().getAuthor());
        assertFalse(info.getCommit().getAuthor().getEmail().isBlank(), "Author email must not be blank");
    }

    @Test
    void parsePush_sample01_commitMessageNonBlank() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertNotNull(info.getCommit());
        assertFalse(
                info.getCommit().getMessage() == null
                        || info.getCommit().getMessage().isBlank(),
                "Commit message must not be blank");
    }

    @Test
    void parsePush_sample01_commitDateNonNullWithPositiveEpoch() throws IOException {
        String packetLine = loadPacketLine("push-sample-01-packetline.txt");
        byte[] body = loadBody("push-sample-01-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertNotNull(info.getCommit());
        assertNotNull(info.getCommit().getDate(), "Commit date must not be null");
        assertTrue(info.getCommit().getDate().getEpochSecond() > 0, "Commit date epoch must be positive");
    }

    // ---- parsePush — sample 02 ----

    @Test
    void parsePush_sample02_extractsOldSha() throws IOException {
        String packetLine = loadPacketLine("push-sample-02-packetline.txt");
        byte[] body = loadBody("push-sample-02-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertEquals(PUSH2_OLD, info.getOldCommit());
    }

    @Test
    void parsePush_sample02_extractsNewSha() throws IOException {
        String packetLine = loadPacketLine("push-sample-02-packetline.txt");
        byte[] body = loadBody("push-sample-02-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertEquals(PUSH2_NEW, info.getNewCommit());
    }

    @Test
    void parsePush_sample02_extractsReference() throws IOException {
        String packetLine = loadPacketLine("push-sample-02-packetline.txt");
        byte[] body = loadBody("push-sample-02-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertEquals(PUSH1_REF, info.getReference()); // Both pushes target refs/heads/main
    }

    @Test
    void parsePush_sample02_commitNotNull() throws IOException {
        String packetLine = loadPacketLine("push-sample-02-packetline.txt");
        byte[] body = loadBody("push-sample-02-body.bin");

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(packetLine, body);

        assertNotNull(info.getCommit(), "Commit must not be null for push 2");
    }

    // ---- branch deletion ----

    @Test
    void parsePush_branchDeletion_returnsNullCommit() throws IOException {
        // newCommit = all zeros signals a branch deletion
        String deletionPacketLine = PUSH1_OLD + " " + ZERO_OID + " refs/heads/feature";

        GitReceivePackParser.PushInfo info = GitReceivePackParser.parsePush(deletionPacketLine, new byte[0]);

        assertNull(info.getCommit(), "Commit must be null for branch deletion");
        assertEquals(ZERO_OID, info.getNewCommit());
    }

    // ---- parsePackData ----

    @Test
    void parsePackData_sample01_returnsCommit() throws IOException {
        byte[] body = loadBody("push-sample-01-body.bin");

        Commit commit = GitReceivePackParser.parsePackData(body);

        assertNotNull(commit, "parsePackData must return a Commit for valid pack data");
    }

    @Test
    void parsePackData_noPackSignature_throwsIOException() {
        byte[] noPackData = "this is not pack data at all".getBytes(StandardCharsets.UTF_8);

        assertThrows(
                IOException.class,
                () -> GitReceivePackParser.parsePackData(noPackData),
                "Expected IOException when no PACK signature is present");
    }

    @Test
    void parsePackData_emptyData_throwsIOException() {
        assertThrows(
                IOException.class,
                () -> GitReceivePackParser.parsePackData(new byte[0]),
                "Expected IOException for empty input");
    }
}
