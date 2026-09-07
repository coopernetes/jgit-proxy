package com.rbc.fogwall.dashboard.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers the email-verification filtering logic used when an SCM OAuth callback locks in provider-verified emails (#40)
 * — see {@code ScmOAuthLinkController.lockProviderVerifiedEmails}. Deserializes real GitHub {@code GET /user/emails}
 * response shapes rather than mocking HTTP, so a field-name drift in the response format would fail this test too.
 */
class ScmOAuthLinkControllerTest {

    @Test
    void verifiedGitHubEmails_includesOnlyVerifiedEntries() {
        String json = """
                [
                    {"email": "verified@example.com", "verified": true, "primary": true},
                    {"email": "unverified@example.com", "verified": false, "primary": false}
                ]
                """;

        var entries = new JsonMapper().readValue(json, ScmOAuthLinkController.GitHubEmailEntry[].class);
        List<String> verified = ScmOAuthLinkController.verifiedGitHubEmails(entries);

        assertEquals(List.of("verified@example.com"), verified);
    }

    @Test
    void verifiedGitHubEmails_emptyWhenNoneVerified() {
        String json = """
                [
                    {"email": "unverified@example.com", "verified": false, "primary": true}
                ]
                """;

        var entries = new JsonMapper().readValue(json, ScmOAuthLinkController.GitHubEmailEntry[].class);
        List<String> verified = ScmOAuthLinkController.verifiedGitHubEmails(entries);

        assertTrue(verified.isEmpty());
    }

    @Test
    void verifiedGitHubEmails_includesMultipleVerifiedEntries() {
        String json = """
                [
                    {"email": "primary@example.com", "verified": true, "primary": true},
                    {"email": "secondary@example.com", "verified": true, "primary": false},
                    {"email": "unverified@example.com", "verified": false, "primary": false}
                ]
                """;

        var entries = new JsonMapper().readValue(json, ScmOAuthLinkController.GitHubEmailEntry[].class);
        List<String> verified = ScmOAuthLinkController.verifiedGitHubEmails(entries);

        assertEquals(List.of("primary@example.com", "secondary@example.com"), verified);
    }

    @Test
    void verifiedForgejoEmails_includesOnlyVerifiedEntries() {
        String json = """
                [
                    {"email": "verified@example.com", "verified": true, "primary": true},
                    {"email": "unverified@example.com", "verified": false, "primary": false}
                ]
                """;

        var entries = new JsonMapper().readValue(json, ScmOAuthLinkController.ForgejoEmailEntry[].class);
        List<String> verified = ScmOAuthLinkController.verifiedForgejoEmails(entries);

        assertEquals(List.of("verified@example.com"), verified);
    }
}
