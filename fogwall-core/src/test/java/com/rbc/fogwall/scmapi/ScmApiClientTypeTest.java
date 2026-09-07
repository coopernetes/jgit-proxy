package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScmApiClientTypeTest {

    /** The four strings are the real headers each CLI sends, captured from the binaries at the pinned versions. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "GitHub CLI 2.98.0|GH_CLI",
                "glab/v1.116.0 (linux, amd64)|GLAB_CLI",
                "tea/0.15.1 (linux/amd64) go-sdk/v1.2.0|TEA_CLI",
                "forgejo-cli/0.6.0 (https://codeberg.org/forgejo-contrib/forgejo-cli/)|FJ_CLI",
            })
    void classifiesEachRealCliUserAgent(String userAgent, ScmApiClientType expected) {
        assertEquals(expected, ScmApiClientType.classify(userAgent));
        assertTrue(ScmApiClientType.classify(userAgent).isKnownCli());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:130.0) Gecko/20100101 Firefox/130.0",
            })
    void classifiesBrowsers(String userAgent) {
        assertEquals(ScmApiClientType.BROWSER, ScmApiClientType.classify(userAgent));
        assertFalse(ScmApiClientType.classify(userAgent).isKnownCli());
    }

    @ParameterizedTest
    @ValueSource(strings = {"curl/8.9.1", "python-requests/2.32.3", "", "   ", "definitely-not-a-cli"})
    void classifiesEverythingElseAsUnknown(String userAgent) {
        assertEquals(ScmApiClientType.UNKNOWN, ScmApiClientType.classify(userAgent));
    }

    @Test
    void missingHeaderIsUnknownRatherThanAnError() {
        assertEquals(ScmApiClientType.UNKNOWN, ScmApiClientType.classify(null));
        assertFalse(ScmApiClientType.UNKNOWN.isKnownCli());
    }

    @Test
    void classificationIsCaseInsensitive() {
        assertEquals(ScmApiClientType.GH_CLI, ScmApiClientType.classify("github cli 2.98.0"));
        assertEquals(ScmApiClientType.TEA_CLI, ScmApiClientType.classify("TEA/0.15.1 (linux/amd64)"));
    }

    /**
     * A CLI whose version has moved on must still classify, because the match is a prefix rather than an exact version
     * — an over-tight match would reject a legitimate upgrade.
     */
    @Test
    void futureCliVersionsStillClassify() {
        assertEquals(ScmApiClientType.GLAB_CLI, ScmApiClientType.classify("glab/v99.0.0 (linux, arm64)"));
        assertEquals(ScmApiClientType.FJ_CLI, ScmApiClientType.classify("forgejo-cli/9.9.9 (https://example.org/)"));
    }

    /**
     * A forged header is classified as whatever it claims — that is unavoidable, and precisely why nothing downstream
     * may branch on the result to grant access. Asserted so the property stays visible.
     */
    @Test
    void aForgedHeaderClassifiesAsTheClaimedCli() {
        assertEquals(ScmApiClientType.TEA_CLI, ScmApiClientType.classify("tea/0.15.1 (forged by curl)"));
    }

    /** One live header per CLI, as captured. glab's leading "v" is dropped so the four are comparable. */
    @Test
    void readsTheVersionEachCliAdvertises() {
        assertEquals("2.98.0", ScmApiClientType.version("GitHub CLI 2.98.0"));
        assertEquals("1.116.0", ScmApiClientType.version("glab/v1.116.0 (linux, amd64)"));
        assertEquals("0.15.1", ScmApiClientType.version("tea/0.15.1 (linux/amd64) go-sdk/v1.2.0"));
        assertEquals(
                "0.6.0",
                ScmApiClientType.version("forgejo-cli/0.6.0 (https://codeberg.org/forgejo-contrib/forgejo-cli/)"));
    }

    /** Best effort by design: no version to read costs the parsed value, never the raw header stored beside it. */
    @Test
    void absentOrUnreadableVersionIsNull() {
        assertNull(ScmApiClientType.version(null));
        assertNull(ScmApiClientType.version(""));
        assertNull(ScmApiClientType.version("curl/8.5.0"));
        assertNull(ScmApiClientType.version("Mozilla/5.0 (X11; Linux x86_64)"));
        assertNull(ScmApiClientType.version("glab/"), "a prefix with nothing after it names no release");
    }

    /**
     * The header is caller-controlled, and this value reaches a fixed-width column. A string no release would carry is
     * treated as absent rather than stored — the raw header still holds whatever was sent.
     */
    @Test
    void implausiblyLongVersionIsTreatedAsAbsent() {
        assertNull(ScmApiClientType.version("glab/" + "9".repeat(500)));
    }
}
