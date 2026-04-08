package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.finos.gitproxy.servlet.filter.UrlRuleFilter.DENIED_BY_ATTRIBUTE;
import static org.finos.gitproxy.servlet.filter.UrlRuleFilter.MATCHED_BY_ATTRIBUTE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.junit.jupiter.api.Test;

class UrlRuleFilterTest {

    private static final GitProxyProvider GITHUB = new GitHubProvider("/proxy");

    private static class FakeResponse {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final AtomicBoolean committed = new AtomicBoolean(false);
        final HttpServletResponse mock;

        FakeResponse() throws IOException {
            mock = mock(HttpServletResponse.class);
            when(mock.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override
                public void write(int b) {
                    body.write(b);
                    committed.set(true);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    body.write(b, off, len);
                    committed.set(true);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener l) {}
            });
            when(mock.isCommitted()).thenAnswer(inv -> committed.get());
        }
    }

    private static ServletInputStream emptyServletInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener l) {}
        };
    }

    private HttpServletRequest mockPushRequest(GitRequestDetails details) throws IOException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(GIT_REQUEST_ATTR, details);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        when(req.getInputStream()).thenReturn(emptyServletInputStream());
        doAnswer(inv -> {
                    attrs.put(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(req)
                .setAttribute(anyString(), any());
        when(req.getAttribute(MATCHED_BY_ATTRIBUTE)).thenAnswer(inv -> attrs.get(MATCHED_BY_ATTRIBUTE));
        when(req.getAttribute(DENIED_BY_ATTRIBUTE)).thenAnswer(inv -> attrs.get(DENIED_BY_ATTRIBUTE));
        return req;
    }

    private GitRequestDetails makeDetails(String owner, String name, String slug) {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner(owner)
                .name(name)
                .slug(slug)
                .build());
        return details;
    }

    // --- UrlRuleFilter ---

    @Test
    void urlRule_orderBelowMinimum_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UrlRuleFilter(49, GITHUB, List.of("owner"), UrlRuleFilter.Target.OWNER));
    }

    @Test
    void urlRule_orderAboveMaximum_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UrlRuleFilter(200, GITHUB, List.of("owner"), UrlRuleFilter.Target.OWNER));
    }

    @Test
    void urlRule_validOrder_succeeds() {
        assertDoesNotThrow(() -> new UrlRuleFilter(50, GITHUB, List.of("owner"), UrlRuleFilter.Target.OWNER));
        assertDoesNotThrow(() -> new UrlRuleFilter(199, GITHUB, List.of("owner"), UrlRuleFilter.Target.OWNER));
    }

    @Test
    void urlRule_applyRule_ownerMatch_setsAttribute() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("allowed-owner"), UrlRuleFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("allowed-owner", "repo", "allowed-owner/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_applyRule_ownerNoMatch_doesNotSetAttribute() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("allowed-owner"), UrlRuleFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("other-owner", "repo", "other-owner/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req, never()).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_applyRule_nameMatch_setsAttribute() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("my-repo"), UrlRuleFilter.Target.NAME);
        GitRequestDetails details = makeDetails("owner", "my-repo", "owner/my-repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_applyRule_slugMatch_setsAttribute() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("/owner/repo"), UrlRuleFilter.Target.SLUG);
        GitRequestDetails details = makeDetails("owner", "repo", "/owner/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_globOwner_matches() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("open-source-*"), UrlRuleFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("open-source-org", "repo", "open-source-org/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_globOwner_noMatch() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("open-source-*"), UrlRuleFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("other-org", "repo", "other-org/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req, never()).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_globSlug_matches() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("/*/public-*"), UrlRuleFilter.Target.SLUG);
        GitRequestDetails details = makeDetails("owner", "public-api", "/owner/public-api");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_globSlug_noMatch() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("/*/public-*"), UrlRuleFilter.Target.SLUG);
        GitRequestDetails details = makeDetails("owner", "private-repo", "/owner/private-repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req, never()).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_globName_matches() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("feature-*"), UrlRuleFilter.Target.NAME);
        GitRequestDetails details = makeDetails("owner", "feature-xyz", "/owner/feature-xyz");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_regexOwner_matches() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("regex:^(myorg|partnerorg)$"), UrlRuleFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("myorg", "repo", "myorg/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_regexOwner_noMatch() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("regex:^(myorg|partnerorg)$"), UrlRuleFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("other-org", "repo", "other-org/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req, never()).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_regexSlug_matches() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("regex:/myorg/.*"), UrlRuleFilter.Target.SLUG);
        GitRequestDetails details = makeDetails("myorg", "any-repo", "/myorg/any-repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRule_beanName_includesProviderAndTargetAndOrder() {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("owner"), UrlRuleFilter.Target.OWNER);
        String name = filter.beanName();
        assertTrue(name.contains("github"));
        assertTrue(name.contains("OWNER"));
        assertTrue(name.contains("100"));
    }

    @Test
    void urlRule_doHttpFilter_isNoOp() throws Exception {
        var filter = new UrlRuleFilter(100, GITHUB, List.of("owner"), UrlRuleFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("owner", "repo", "/owner/repo");
        FakeResponse resp = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "doHttpFilter on UrlRuleFilter should be no-op");
    }

    // --- UrlRuleAggregateFilter ---

    @Test
    void urlRuleAggregate_orderBelowMinimum_throws() {
        assertThrows(IllegalArgumentException.class, () -> new UrlRuleAggregateFilter(49, GITHUB, List.of()));
    }

    @Test
    void urlRuleAggregate_ruleMatches_passes() throws Exception {
        var ownerFilter = new UrlRuleFilter(100, GITHUB, List.of("owner"), UrlRuleFilter.Target.OWNER);
        var aggregate = new UrlRuleAggregateFilter(50, GITHUB, List.of(ownerFilter));
        GitRequestDetails details = makeDetails("owner", "repo", "/owner/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Request matching an allow rule should pass");
    }

    @Test
    void urlRuleAggregate_noRuleMatch_blocks() throws Exception {
        var ownerFilter = new UrlRuleFilter(100, GITHUB, List.of("allowed"), UrlRuleFilter.Target.OWNER);
        var aggregate = new UrlRuleAggregateFilter(50, Set.of(HttpOperation.PUSH), GITHUB, List.of(ownerFilter));
        GitRequestDetails details = makeDetails("not-allowed", "repo", "/not-allowed/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Request not matching any allow rule should be blocked");
    }

    @Test
    void urlRuleAggregate_emptyRules_blocks() throws Exception {
        var aggregate = new UrlRuleAggregateFilter(50, Set.of(HttpOperation.PUSH), GITHUB, List.of());
        GitRequestDetails details = makeDetails("owner", "repo", "/owner/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "No allow rules configured — default-deny should block all requests");
    }

    @Test
    void urlRule_denyAccess_applyRule_setsdeniedByAttribute() throws Exception {
        var filter = new UrlRuleFilter(
                100, GITHUB, List.of("blocked-owner"), UrlRuleFilter.Target.OWNER, AccessRule.Access.DENY);
        GitRequestDetails details = makeDetails("blocked-owner", "repo", "blocked-owner/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyRule(req);

        verify(req).setAttribute(eq(DENIED_BY_ATTRIBUTE), anyString());
        verify(req, never()).setAttribute(eq(MATCHED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void urlRuleAggregate_denyRuleMatches_blocks() throws Exception {
        var denyFilter = new UrlRuleFilter(
                100, GITHUB, List.of("blocked-owner"), UrlRuleFilter.Target.OWNER, AccessRule.Access.DENY);
        var allowFilter = new UrlRuleFilter(100, GITHUB, List.of("blocked-owner"), UrlRuleFilter.Target.OWNER);
        var aggregate =
                new UrlRuleAggregateFilter(50, Set.of(HttpOperation.PUSH), GITHUB, List.of(denyFilter, allowFilter));
        GitRequestDetails details = makeDetails("blocked-owner", "repo", "/blocked-owner/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Deny rule should block even when allow rule also matches");
    }

    @Test
    void urlRuleAggregate_denyRuleNoMatch_allowRuleMatches_passes() throws Exception {
        var denyFilter = new UrlRuleFilter(
                100, GITHUB, List.of("blocked-owner"), UrlRuleFilter.Target.OWNER, AccessRule.Access.DENY);
        var allowFilter = new UrlRuleFilter(100, GITHUB, List.of("allowed-owner"), UrlRuleFilter.Target.OWNER);
        var aggregate =
                new UrlRuleAggregateFilter(50, Set.of(HttpOperation.PUSH), GITHUB, List.of(denyFilter, allowFilter));
        GitRequestDetails details = makeDetails("allowed-owner", "repo", "/allowed-owner/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Non-denied, allowed request should pass");
    }

    @Test
    void urlRuleAggregate_denyRulesOnly_nonMatchedBlocks() throws Exception {
        var denyFilter = new UrlRuleFilter(
                100, GITHUB, List.of("blocked-owner"), UrlRuleFilter.Target.OWNER, AccessRule.Access.DENY);
        var aggregate = new UrlRuleAggregateFilter(50, Set.of(HttpOperation.PUSH), GITHUB, List.of(denyFilter));
        GitRequestDetails details = makeDetails("other-owner", "repo", "/other-owner/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(
                resp.committed.get(),
                "No allow rules configured — default-deny blocks even requests that miss deny rules");
    }
}
