package org.finos.gitproxy.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.Contributor;
import org.junit.jupiter.api.Test;

class GpgSignatureCheckTest {

    private static Commit unsignedCommit() {
        return Commit.builder()
                .sha("abc1234567890")
                .author(Contributor.builder()
                        .name("Alice")
                        .email("alice@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Alice")
                        .email("alice@example.com")
                        .build())
                .message("feat: add thing")
                .date(Instant.ofEpochSecond(1_700_000_000L))
                .build();
    }

    private static Commit signedCommit(String signature) {
        return Commit.builder()
                .sha("abc1234567890")
                .author(Contributor.builder()
                        .name("Alice")
                        .email("alice@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Alice")
                        .email("alice@example.com")
                        .build())
                .message("feat: add thing")
                .date(Instant.ofEpochSecond(1_700_000_000L))
                .signature(signature)
                .build();
    }

    @Test
    void disabled_returnsNoViolations() {
        GpgConfig config = GpgConfig.builder().enabled(false).build();
        GpgSignatureCheck check = new GpgSignatureCheck(config);
        assertTrue(check.check(List.of(unsignedCommit())).isEmpty());
    }

    @Test
    void nullConfig_treatsAsDisabled() {
        GpgSignatureCheck check = new GpgSignatureCheck(null);
        assertTrue(check.check(List.of(unsignedCommit())).isEmpty());
    }

    @Test
    void enabled_emptyCommitList_returnsNoViolations() {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        GpgSignatureCheck check = new GpgSignatureCheck(config);
        assertTrue(check.check(List.of()).isEmpty());
    }

    @Test
    void requireSigned_unsignedCommit_returnsViolation() {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        GpgSignatureCheck check = new GpgSignatureCheck(config);
        List<Violation> violations = check.check(List.of(unsignedCommit()));
        assertEquals(1, violations.size());
        assertEquals("not signed", violations.get(0).reason());
    }

    @Test
    void requireSigned_emptySignatureString_returnsViolation() {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(true).build();
        GpgSignatureCheck check = new GpgSignatureCheck(config);
        List<Violation> violations = check.check(List.of(signedCommit("")));
        assertEquals(1, violations.size());
        assertEquals("not signed", violations.get(0).reason());
    }

    @Test
    void signedCommit_malformedSignature_returnsViolation() {
        GpgConfig config =
                GpgConfig.builder().enabled(true).requireSignedCommits(false).build();
        GpgSignatureCheck check = new GpgSignatureCheck(config);
        List<Violation> violations = check.check(List.of(signedCommit("not a valid pgp signature")));
        assertEquals(1, violations.size());
        assertEquals("invalid signature", violations.get(0).reason());
    }
}
