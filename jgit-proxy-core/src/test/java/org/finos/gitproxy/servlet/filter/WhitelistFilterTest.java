package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.finos.gitproxy.servlet.filter.WhitelistByUrlFilter.WHITELISTED_BY_ATTRIBUTE;
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
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.junit.jupiter.api.Test;

class WhitelistFilterTest {

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
        when(req.getAttribute(WHITELISTED_BY_ATTRIBUTE)).thenAnswer(inv -> attrs.get(WHITELISTED_BY_ATTRIBUTE));
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

    // --- WhitelistByUrlFilter ---

    @Test
    void whitelistByUrl_orderBelowMinimum_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WhitelistByUrlFilter(999, GITHUB, List.of("owner"), AuthorizedByUrlFilter.Target.OWNER));
    }

    @Test
    void whitelistByUrl_orderAboveMaximum_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WhitelistByUrlFilter(2000, GITHUB, List.of("owner"), AuthorizedByUrlFilter.Target.OWNER));
    }

    @Test
    void whitelistByUrl_validOrder_succeeds() {
        assertDoesNotThrow(
                () -> new WhitelistByUrlFilter(1000, GITHUB, List.of("owner"), AuthorizedByUrlFilter.Target.OWNER));
        assertDoesNotThrow(
                () -> new WhitelistByUrlFilter(1999, GITHUB, List.of("owner"), AuthorizedByUrlFilter.Target.OWNER));
    }

    @Test
    void whitelistByUrl_applyWhitelist_ownerMatch_setsAttribute() throws Exception {
        var filter =
                new WhitelistByUrlFilter(1500, GITHUB, List.of("allowed-owner"), AuthorizedByUrlFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("allowed-owner", "repo", "allowed-owner/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyWhitelist(req);

        verify(req).setAttribute(eq(WHITELISTED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void whitelistByUrl_applyWhitelist_ownerNoMatch_doesNotSetAttribute() throws Exception {
        var filter =
                new WhitelistByUrlFilter(1500, GITHUB, List.of("allowed-owner"), AuthorizedByUrlFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("other-owner", "repo", "other-owner/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyWhitelist(req);

        verify(req, never()).setAttribute(eq(WHITELISTED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void whitelistByUrl_applyWhitelist_nameMatch_setsAttribute() throws Exception {
        var filter = new WhitelistByUrlFilter(1500, GITHUB, List.of("my-repo"), AuthorizedByUrlFilter.Target.NAME);
        GitRequestDetails details = makeDetails("owner", "my-repo", "owner/my-repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyWhitelist(req);

        verify(req).setAttribute(eq(WHITELISTED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void whitelistByUrl_applyWhitelist_slugMatch_setsAttribute() throws Exception {
        var filter = new WhitelistByUrlFilter(1500, GITHUB, List.of("owner/repo"), AuthorizedByUrlFilter.Target.SLUG);
        GitRequestDetails details = makeDetails("owner", "repo", "owner/repo");
        HttpServletRequest req = mockPushRequest(details);

        filter.applyWhitelist(req);

        verify(req).setAttribute(eq(WHITELISTED_BY_ATTRIBUTE), anyString());
    }

    @Test
    void whitelistByUrl_beanName_includesProviderAndTargetAndOrder() {
        var filter = new WhitelistByUrlFilter(1500, GITHUB, List.of("owner"), AuthorizedByUrlFilter.Target.OWNER);
        String name = filter.beanName();
        assertTrue(name.contains("github"));
        assertTrue(name.contains("OWNER"));
        assertTrue(name.contains("1500"));
    }

    @Test
    void whitelistByUrl_doHttpFilter_isNoOp() throws Exception {
        var filter = new WhitelistByUrlFilter(1500, GITHUB, List.of("owner"), AuthorizedByUrlFilter.Target.OWNER);
        GitRequestDetails details = makeDetails("owner", "repo", "owner/repo");
        FakeResponse resp = new FakeResponse();

        filter.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "doHttpFilter on WhitelistByUrlFilter should be no-op");
    }

    // --- WhitelistAggregateFilter ---

    @Test
    void whitelistAggregate_orderBelowMinimum_throws() {
        assertThrows(IllegalArgumentException.class, () -> new WhitelistAggregateFilter(999, GITHUB, List.of()));
    }

    @Test
    void whitelistAggregate_whitelistMatches_passes() throws Exception {
        var ownerFilter = new WhitelistByUrlFilter(1500, GITHUB, List.of("owner"), AuthorizedByUrlFilter.Target.OWNER);
        var aggregate = new WhitelistAggregateFilter(1000, GITHUB, List.of(ownerFilter));
        GitRequestDetails details = makeDetails("owner", "repo", "owner/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Request matching whitelist should pass");
    }

    @Test
    void whitelistAggregate_noWhitelistMatch_blocks() throws Exception {
        var ownerFilter =
                new WhitelistByUrlFilter(1500, GITHUB, List.of("allowed"), AuthorizedByUrlFilter.Target.OWNER);
        var aggregate = new WhitelistAggregateFilter(1000, Set.of(HttpOperation.PUSH), GITHUB, List.of(ownerFilter));
        GitRequestDetails details = makeDetails("not-allowed", "repo", "not-allowed/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Request not matching any whitelist should be blocked");
    }

    @Test
    void whitelistAggregate_emptyWhitelist_blocks() throws Exception {
        var aggregate = new WhitelistAggregateFilter(1000, Set.of(HttpOperation.PUSH), GITHUB, List.of());
        GitRequestDetails details = makeDetails("owner", "repo", "owner/repo");
        FakeResponse resp = new FakeResponse();

        aggregate.doHttpFilter(mockPushRequest(details), resp.mock);

        assertTrue(resp.committed.get(), "Empty whitelist should block all requests");
    }
}
