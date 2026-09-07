package com.rbc.fogwall.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.config.BlockConfig;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BlockedContentDiffCheckTest {

    private static BlockedContentDiffCheck checkWithLiterals(String... literals) {
        return new BlockedContentDiffCheck(
                BlockConfig.builder().literals(List.of(literals)).build());
    }

    private static BlockedContentDiffCheck checkWithPattern(String pattern) {
        return new BlockedContentDiffCheck(BlockConfig.builder()
                .patterns(List.of(Pattern.compile(pattern)))
                .build());
    }

    private static final String SAMPLE_DIFF = """
            diff --git a/src/Foo.java b/src/Foo.java
            index abc..def 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -1,3 +1,4 @@
             unchanged line
            +added line with secret content
            -removed line with secret content
            """;

    @Test
    void noRulesConfigured_returnsEmpty() {
        BlockedContentDiffCheck check =
                new BlockedContentDiffCheck(BlockConfig.builder().build());
        Optional<List<Violation>> result = check.check(SAMPLE_DIFF);
        assertTrue(result.isPresent());
        assertTrue(result.get().isEmpty());
    }

    @Test
    void blockedLiteral_addedLine_returnsViolation() {
        BlockedContentDiffCheck check = checkWithLiterals("secret");
        List<Violation> violations = check.check(SAMPLE_DIFF).orElseThrow();
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).reason().contains("secret"));
        assertTrue(violations.get(0).reason().contains("src/Foo.java"));
    }

    @Test
    void blockedLiteral_caseInsensitiveMatch() {
        BlockedContentDiffCheck check = checkWithLiterals("SECRET");
        List<Violation> violations = check.check(SAMPLE_DIFF).orElseThrow();
        assertFalse(violations.isEmpty());
    }

    @Test
    void blockedLiteral_removedLineNotScanned() {
        BlockedContentDiffCheck check = checkWithLiterals("removed");
        List<Violation> violations = check.check(SAMPLE_DIFF).orElseThrow();
        assertTrue(violations.isEmpty());
    }

    @Test
    void blockedLiteral_contextLineNotScanned() {
        BlockedContentDiffCheck check = checkWithLiterals("unchanged");
        List<Violation> violations = check.check(SAMPLE_DIFF).orElseThrow();
        assertTrue(violations.isEmpty());
    }

    @Test
    void blockedPattern_addedLine_returnsViolation() {
        BlockedContentDiffCheck check = checkWithPattern("s[e3]cret");
        List<Violation> violations = check.check(SAMPLE_DIFF).orElseThrow();
        assertFalse(violations.isEmpty());
        assertTrue(violations.get(0).reason().contains("s[e3]cret"));
    }

    @Test
    void emptyDiff_noViolations() {
        BlockedContentDiffCheck check = checkWithLiterals("secret");
        assertTrue(check.check("").orElseThrow().isEmpty());
    }

    @Test
    void deduplicatesViolations_sameTermMultipleLines() {
        String diff = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1,2 @@
                +line with password here
                +another line with password here
                """;
        BlockedContentDiffCheck check = checkWithLiterals("password");
        List<Violation> violations = check.check(diff).orElseThrow();
        assertEquals(1, violations.size());
    }

    @Test
    void violationFormattedDetailIncludesMatchingLine() {
        BlockedContentDiffCheck check = checkWithLiterals("secret");
        List<Violation> violations = check.check(SAMPLE_DIFF).orElseThrow();
        assertEquals(1, violations.size());
        String detail = violations.get(0).formattedDetail();
        assertNotNull(detail);
        assertTrue(detail.contains("src/Foo.java"), "detail must reference the file");
        assertTrue(detail.contains("added line with secret content"), "detail must include the matching line");
    }

    @Test
    void deduplication_formattedDetailContainsFirstMatchingLine() {
        String diff = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1,2 @@
                +first password occurrence
                +second password occurrence
                """;
        BlockedContentDiffCheck check = checkWithLiterals("password");
        List<Violation> violations = check.check(diff).orElseThrow();
        assertEquals(1, violations.size());
        assertTrue(
                violations.get(0).formattedDetail().contains("first password occurrence"),
                "formattedDetail should show the first matching line");
    }

    @Test
    void extractFileName_standardHeader() {
        assertEquals(
                "src/Foo.java", BlockedContentDiffCheck.extractFileName("diff --git a/src/Foo.java b/src/Foo.java"));
    }

    @Test
    void extractFileName_noPrefix() {
        assertEquals(
                "path/file.txt", BlockedContentDiffCheck.extractFileName("diff --git a/path/file.txt path/file.txt"));
    }

    @Test
    void violationIncludesFileName() {
        String diff = """
                diff --git a/config.yml b/config.yml
                --- a/config.yml
                +++ b/config.yml
                @@ -1 +1 @@
                +api_key: supersecret
                """;
        BlockedContentDiffCheck check = checkWithLiterals("supersecret");
        List<Violation> violations = check.check(diff).orElseThrow();
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).reason().contains("config.yml"));
    }
}
