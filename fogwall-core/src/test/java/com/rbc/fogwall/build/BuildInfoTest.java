package com.rbc.fogwall.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class BuildInfoTest {

    private static BuildInfo of(String version, String commit) {
        var props = new Properties();
        if (version != null) props.setProperty("version", version);
        if (commit != null) props.setProperty("commit", commit);
        return BuildInfo.from(props);
    }

    // ── Parsing rules ─────────────────────────────────────────────────────────

    @Test
    void readsBothValues() {
        BuildInfo info = of("1.3.2", "f04b5ffc6f40cd07fc838c9af96482d3afadd052");
        assertEquals("1.3.2", info.version());
        assertEquals("f04b5ffc6f40cd07fc838c9af96482d3afadd052", info.commit());
    }

    @Test
    void unexpandedPlaceholder_readsAsUnknown() {
        // A classpath assembled without running processResources still carries the template. Reporting
        // "${buildVersion}" as a version would be worse than admitting the build is unknown.
        BuildInfo info = of("${buildVersion}", "${buildCommit}");
        assertEquals(BuildInfo.UNKNOWN, info.version());
        assertEquals(BuildInfo.UNKNOWN, info.commit());
    }

    @Test
    void missingOrBlankValues_readAsUnknown() {
        assertEquals(BuildInfo.UNKNOWN, of(null, null).version());
        assertEquals(BuildInfo.UNKNOWN, of(null, null).commit());
        assertEquals(BuildInfo.UNKNOWN, of("  ", "  ").version());
        assertEquals(BuildInfo.UNKNOWN, of("  ", "  ").commit());
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertEquals("1.3.2", of(" 1.3.2 ", null).version());
    }

    @Test
    void versionAndCommitAreIndependent() {
        // The container build passes a commit in; a build without one still names its version.
        BuildInfo info = of("1.3.2", null);
        assertEquals("1.3.2", info.version());
        assertEquals(BuildInfo.UNKNOWN, info.commit());
    }

    // ── Derived forms ─────────────────────────────────────────────────────────

    @Test
    void shortCommit_isAbbreviated() {
        assertEquals(
                "f04b5ff",
                of("1.3.2", "f04b5ffc6f40cd07fc838c9af96482d3afadd052").shortCommit());
    }

    @Test
    void shortCommit_ofUnknown_staysUnknown() {
        assertEquals(BuildInfo.UNKNOWN, of("1.3.2", null).shortCommit());
    }

    @Test
    void shortCommit_ofAShortValue_doesNotOverrun() {
        assertEquals("abc", of("1.3.2", "abc").shortCommit());
    }

    @Test
    void display_namesVersionAndShortCommit() {
        assertEquals(
                "1.3.2 (f04b5ff)",
                of("1.3.2", "f04b5ffc6f40cd07fc838c9af96482d3afadd052").display());
        assertEquals("1.3.2 (unknown)", of("1.3.2", null).display());
    }

    // ── The real build stamp on this classpath ────────────────────────────────

    @Test
    void resourceOnTheTestClasspathIsExpanded() {
        // Guards the Gradle side: if processResources stopped expanding fogwall-build.properties, or shipped a
        // stale copy, the running process would report a placeholder or nothing at all.
        BuildInfo info = BuildInfo.get();
        assertNotNull(info.version());
        assertFalse(info.version().startsWith("${"), "version was not expanded: " + info.version());
        assertFalse(info.commit().startsWith("${"), "commit was not expanded: " + info.commit());
        assertNotEqualsUnknown(info.version());
    }

    private static void assertNotEqualsUnknown(String version) {
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), "expected a real version, got: " + version);
    }
}
