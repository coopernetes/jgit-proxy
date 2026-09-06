package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitLabRestAllowlistTest {

    @Test
    void matchesIssueCreate() {
        Optional<ScmApiRestMatch> match = GitLabRestAllowlist.match("POST", "/projects/acme%2Fwidgets/issues");
        assertTrue(match.isPresent());
        assertEquals("issues.create", match.get().operation());
        assertEquals("acme", match.get().ownerRepo().owner());
        assertEquals("widgets", match.get().ownerRepo().name());
    }

    @Test
    void matchesIssueUpdateAndClose_sameEndpoint() {
        Optional<ScmApiRestMatch> match = GitLabRestAllowlist.match("PUT", "/projects/acme%2Fwidgets/issues/2");
        assertTrue(match.isPresent());
        assertEquals("issues.update", match.get().operation());
    }

    @Test
    void matchesIssueNote() {
        Optional<ScmApiRestMatch> match = GitLabRestAllowlist.match("POST", "/projects/acme%2Fwidgets/issues/2/notes");
        assertTrue(match.isPresent());
        assertEquals("issues.note", match.get().operation());
    }

    @Test
    void matchesMrCreate() {
        Optional<ScmApiRestMatch> match = GitLabRestAllowlist.match("POST", "/projects/acme%2Fwidgets/merge_requests");
        assertTrue(match.isPresent());
        assertEquals("merge_requests.create", match.get().operation());
    }

    @Test
    void matchesMrUpdateAndClose_sameEndpoint() {
        Optional<ScmApiRestMatch> match = GitLabRestAllowlist.match("PUT", "/projects/acme%2Fwidgets/merge_requests/3");
        assertTrue(match.isPresent());
        assertEquals("merge_requests.update", match.get().operation());
    }

    @Test
    void matchesMrNote() {
        Optional<ScmApiRestMatch> match =
                GitLabRestAllowlist.match("POST", "/projects/acme%2Fwidgets/merge_requests/3/notes");
        assertTrue(match.isPresent());
        assertEquals("merge_requests.note", match.get().operation());
    }

    /** Review is out of scope — reviewers use GitLab's own UI, so approval is never forwarded. */
    @Test
    void deniesMrApprove() {
        assertTrue(GitLabRestAllowlist.match("POST", "/projects/acme%2Fwidgets/merge_requests/3/approve")
                .isEmpty());
        assertTrue(GitLabRestAllowlist.match("POST", "/projects/acme%2Fwidgets/merge_requests/3/unapprove")
                .isEmpty());
    }

    @Test
    void denies_unrecognizedPath() {
        assertTrue(GitLabRestAllowlist.match("POST", "/projects/acme%2Fwidgets/labels")
                .isEmpty());
    }

    @Test
    void denies_unrecognizedMethod() {
        assertTrue(GitLabRestAllowlist.match("DELETE", "/projects/acme%2Fwidgets/issues/2")
                .isEmpty());
    }

    @Test
    void denies_numericProjectId_notPathBased() {
        assertTrue(GitLabRestAllowlist.match("POST", "/projects/12345/issues").isEmpty());
    }

    @Test
    void denies_nullMethodOrPath() {
        assertTrue(GitLabRestAllowlist.match(null, "/projects/acme%2Fwidgets/issues")
                .isEmpty());
        assertTrue(GitLabRestAllowlist.match("POST", null).isEmpty());
    }

    @Test
    void groupPathWithNestedNamespace_decodesCorrectly() {
        // GitLab subgroups: "group/subgroup/project" URL-encoded as group%2Fsubgroup%2Fproject.
        // owner becomes "group/subgroup" and name is the final segment, matching path_with_namespace convention.
        Optional<ScmApiRestMatch> match =
                GitLabRestAllowlist.match("POST", "/projects/group%2Fsubgroup%2Fproject/issues");
        assertTrue(match.isPresent());
        assertEquals("group/subgroup", match.get().ownerRepo().owner());
        assertEquals("project", match.get().ownerRepo().name());
    }
}
