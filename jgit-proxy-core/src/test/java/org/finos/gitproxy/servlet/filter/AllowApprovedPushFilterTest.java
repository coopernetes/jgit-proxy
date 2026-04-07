package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.finos.gitproxy.servlet.GitProxyServlet.PRE_APPROVED_ATTR;
import static org.finos.gitproxy.servlet.GitProxyServlet.SERVICE_URL_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.finos.gitproxy.db.memory.InMemoryPushStore;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.git.GitRequestDetails;
import org.junit.jupiter.api.Test;

class AllowApprovedPushFilterTest {

    private static HttpServletRequest mockPushRequest(GitRequestDetails details) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        when(req.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());
        return req;
    }

    private static GitRequestDetails pushDetailsFor(String commitTo, String branch, String repoName) {
        GitRequestDetails details = new GitRequestDetails();
        details.setCommitTo(commitTo);
        details.setBranch(branch);
        details.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner("owner")
                .name(repoName)
                .slug("/owner/" + repoName)
                .build());
        details.setCommit(org.finos.gitproxy.git.Commit.builder()
                .sha(commitTo)
                .author(org.finos.gitproxy.git.Contributor.builder()
                        .name("Dev")
                        .email("dev@example.com")
                        .build())
                .committer(org.finos.gitproxy.git.Contributor.builder()
                        .name("Dev")
                        .email("dev@example.com")
                        .build())
                .message("test")
                .build());
        return details;
    }

    @Test
    void noApprovedRecord_doesNotSetPreApproved() throws Exception {
        InMemoryPushStore store = new InMemoryPushStore();
        AllowApprovedPushFilter filter = new AllowApprovedPushFilter(store, "http://localhost:8080");
        GitRequestDetails details = pushDetailsFor("abc123", "refs/heads/main", "my-repo");
        HttpServletRequest req = mockPushRequest(details);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doHttpFilter(req, resp);

        verify(req, never()).setAttribute(eq(PRE_APPROVED_ATTR), any());
    }

    @Test
    void approvedRecord_setsPreApproved() throws Exception {
        InMemoryPushStore store = new InMemoryPushStore();
        // Save an approved push record for the same commitTo + branch + repo
        PushRecord approved = PushRecord.builder()
                .commitTo("deadbeef")
                .branch("refs/heads/main")
                .repoName("my-repo")
                .build();
        store.save(approved);
        store.approve(
                approved.getId(),
                Attestation.builder()
                        .pushId(approved.getId())
                        .type(Attestation.Type.APPROVAL)
                        .reviewerUsername("admin")
                        .build());

        AllowApprovedPushFilter filter = new AllowApprovedPushFilter(store, "http://localhost:8080");
        GitRequestDetails details = pushDetailsFor("deadbeef", "refs/heads/main", "my-repo");
        HttpServletRequest req = mockPushRequest(details);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doHttpFilter(req, resp);

        verify(req).setAttribute(PRE_APPROVED_ATTR, Boolean.TRUE);
    }

    @Test
    void alwaysSetsServiceUrlAttribute() throws Exception {
        InMemoryPushStore store = new InMemoryPushStore();
        AllowApprovedPushFilter filter = new AllowApprovedPushFilter(store, "http://my-dashboard:8080");
        GitRequestDetails details = pushDetailsFor("abc", "refs/heads/main", "repo");
        HttpServletRequest req = mockPushRequest(details);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doHttpFilter(req, resp);

        verify(req).setAttribute(SERVICE_URL_ATTR, "http://my-dashboard:8080");
    }

    @Test
    void approvedRecordForDifferentCommit_doesNotSetPreApproved() throws Exception {
        InMemoryPushStore store = new InMemoryPushStore();
        PushRecord approved = PushRecord.builder()
                .commitTo("aaaaaa")
                .branch("refs/heads/main")
                .repoName("repo")
                .build();
        store.save(approved);
        store.approve(
                approved.getId(),
                Attestation.builder()
                        .pushId(approved.getId())
                        .type(Attestation.Type.APPROVAL)
                        .reviewerUsername("admin")
                        .build());

        AllowApprovedPushFilter filter = new AllowApprovedPushFilter(store, "http://localhost:8080");
        // Different commitTo
        GitRequestDetails details = pushDetailsFor("bbbbbb", "refs/heads/main", "repo");
        HttpServletRequest req = mockPushRequest(details);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doHttpFilter(req, resp);

        verify(req, never()).setAttribute(eq(PRE_APPROVED_ATTR), any());
    }

    @Test
    void tagPush_approvedRecord_setsPreApproved() throws Exception {
        // Tag pushes have commit == null; the filter must still honour a prior approval.
        InMemoryPushStore store = new InMemoryPushStore();
        PushRecord approved = PushRecord.builder()
                .commitTo("tagsha123")
                .branch("refs/tags/v1.0")
                .repoName("my-repo")
                .build();
        store.save(approved);
        store.approve(
                approved.getId(),
                Attestation.builder()
                        .pushId(approved.getId())
                        .type(Attestation.Type.APPROVAL)
                        .reviewerUsername("admin")
                        .build());

        AllowApprovedPushFilter filter = new AllowApprovedPushFilter(store, "http://localhost:8080");

        // No commit set — mirrors what ParseGitRequestFilter produces for a tag push
        GitRequestDetails details = new GitRequestDetails();
        details.setCommitTo("tagsha123");
        details.setBranch("refs/tags/v1.0");
        details.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner("owner")
                .name("my-repo")
                .slug("/owner/my-repo")
                .build());

        HttpServletRequest req = mockPushRequest(details);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doHttpFilter(req, resp);

        verify(req).setAttribute(PRE_APPROVED_ATTR, Boolean.TRUE);
    }

    @Test
    void blankCommitTo_skipsLookup() throws Exception {
        org.finos.gitproxy.db.PushStore store = mock(org.finos.gitproxy.db.PushStore.class);
        AllowApprovedPushFilter filter = new AllowApprovedPushFilter(store, "http://localhost:8080");
        GitRequestDetails details = pushDetailsFor("", "refs/heads/main", "repo");
        HttpServletRequest req = mockPushRequest(details);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doHttpFilter(req, resp);

        verify(store, never()).find(any());
    }
}
