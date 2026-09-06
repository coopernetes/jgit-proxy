package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.provider.GitLabProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link GitLabProjectIdResolver} against a local stub of GitLab's {@code GET /projects/:id}. This is the
 * lookup that lets a fork merge request be authorized against the upstream it targets rather than the fork named in its
 * URL, so the failure modes matter as much as the happy path.
 */
class GitLabProjectIdResolverTest {

    private HttpServer server;
    private GitLabProvider provider;
    private final InMemoryProjectIdCache cache = new InMemoryProjectIdCache();
    private String responseBody;
    private int responseStatus = 200;
    private final List<String> requestedPaths = new ArrayList<>();
    private final List<String> seenAuthHeaders = new ArrayList<>();
    private final List<String> seenUserAgents = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v4/projects", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            String privateToken = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            seenAuthHeaders.add(
                    privateToken != null ? "PRIVATE-TOKEN:" + privateToken : "Authorization:" + authorization);
            seenUserAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        provider = GitLabProvider.builder()
                .apiUri(URI.create("http://localhost:" + server.getAddress().getPort()))
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private GitLabProjectIdResolver resolver() {
        return new GitLabProjectIdResolver(cache);
    }

    @Test
    void cacheHit_neverCallsUpstream() {
        cache.store("gitlab", "42", new OwnerRepo("acme", "widgets"));

        Optional<OwnerRepo> result = resolver().resolve(provider, "42", "PRIVATE-TOKEN", "glpat-x");

        assertEquals(Optional.of(new OwnerRepo("acme", "widgets")), result);
        assertTrue(requestedPaths.isEmpty(), "a cache hit must not reach upstream");
    }

    @Test
    void cacheMiss_resolvesPathWithNamespace_andPopulatesCache() {
        responseBody = "{\"id\":42,\"path_with_namespace\":\"acme/widgets\"}";

        Optional<OwnerRepo> result = resolver().resolve(provider, "42", "PRIVATE-TOKEN", "glpat-x");

        assertEquals(Optional.of(new OwnerRepo("acme", "widgets")), result);
        assertEquals(List.of("/api/v4/projects/42"), requestedPaths);
        assertEquals(Optional.of(new OwnerRepo("acme", "widgets")), cache.lookup("gitlab", "42"));
    }

    /**
     * fogwall originates this lookup rather than brokering it, so it identifies itself. Presenting the caller's CLI
     * would attribute to that CLI a request it never sent; presenting the HTTP client library's default would name the
     * library and the Java runtime.
     */
    @Test
    void identifiesItselfOnItsOwnLookup() {
        responseBody = "{\"id\":42,\"path_with_namespace\":\"acme/widgets\"}";

        resolver().resolve(provider, "42", "PRIVATE-TOKEN", "glpat-x");

        assertEquals(List.of("fogwall"), seenUserAgents);
    }

    /** GitLab nests groups, so everything before the last separator is the owner. */
    @Test
    void resolvesANestedGroupPath() {
        responseBody = "{\"path_with_namespace\":\"group/subgroup/widgets\"}";

        Optional<OwnerRepo> result = resolver().resolve(provider, "7", "PRIVATE-TOKEN", "glpat-x");

        assertEquals(Optional.of(new OwnerRepo("group/subgroup", "widgets")), result);
    }

    /** The caller's credential is presented in the header they used — fogwall does not re-scheme it. */
    @Test
    void presentsTheCallersOwnCredentialHeader() {
        responseBody = "{\"path_with_namespace\":\"acme/widgets\"}";

        resolver().resolve(provider, "42", "PRIVATE-TOKEN", "glpat-x");
        resolver().resolve(provider, "43", "Authorization", "Bearer oauth-x");

        assertEquals(List.of("PRIVATE-TOKEN:glpat-x", "Authorization:Bearer oauth-x"), seenAuthHeaders);
    }

    @Test
    void malformedOrUnexpectedResponse_returnsEmpty_andDoesNotPopulateCache() {
        responseBody = "{\"id\":42}"; // no path_with_namespace
        assertTrue(resolver().resolve(provider, "42", "PRIVATE-TOKEN", "t").isEmpty());

        responseBody = "not json";
        assertTrue(resolver().resolve(provider, "43", "PRIVATE-TOKEN", "t").isEmpty());

        responseBody = "{\"path_with_namespace\":\"noslash\"}";
        assertTrue(resolver().resolve(provider, "44", "PRIVATE-TOKEN", "t").isEmpty());

        assertTrue(cache.lookup("gitlab", "42").isEmpty());
        assertTrue(cache.lookup("gitlab", "43").isEmpty());
        assertTrue(cache.lookup("gitlab", "44").isEmpty());
    }

    /** A 404 for a project the caller's token cannot see must not resolve — the gate filter then denies. */
    @Test
    void upstreamErrorStatus_returnsEmpty() {
        responseStatus = 404;
        responseBody = "{\"message\":\"404 Project Not Found\"}";

        assertTrue(resolver().resolve(provider, "99", "PRIVATE-TOKEN", "t").isEmpty());
        assertTrue(cache.lookup("gitlab", "99").isEmpty());
    }

    private static final class InMemoryProjectIdCache implements GitLabProjectIdCache {
        private final Map<String, OwnerRepo> entries = new HashMap<>();

        @Override
        public Optional<OwnerRepo> lookup(String provider, String projectId) {
            return Optional.ofNullable(entries.get(provider + ":" + projectId));
        }

        @Override
        public void store(String provider, String projectId, OwnerRepo ownerRepo) {
            entries.put(provider + ":" + projectId, ownerRepo);
        }
    }
}
