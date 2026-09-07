package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.provider.GitHubProvider;
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
 * Exercises {@link GitHubNodeIdResolver} against a local stub GraphQL endpoint, covering the response-parsing and
 * cache-seeding behaviour.
 */
class GitHubNodeIdResolverTest {

    private HttpServer server;
    private GitHubProvider provider;
    private final InMemoryGitHubNodeIdCache cache = new InMemoryGitHubNodeIdCache();
    private String lastResponseBody;
    private final List<String> seenUserAgents = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/graphql", exchange -> {
            seenUserAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] response = lastResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        provider = GitHubProvider.builder()
                .apiUri(URI.create("http://localhost:" + server.getAddress().getPort()))
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void cacheHit_neverCallsUpstream() {
        cache.store("github", "R_1", new OwnerRepo("acme", "widgets"));
        lastResponseBody = "should not be read";

        Optional<OwnerRepo> result =
                new GitHubNodeIdResolver(cache).resolve(provider, new MutationNodeIdRef("R_1", null), "token");

        assertEquals(Optional.of(new OwnerRepo("acme", "widgets")), result);
    }

    @Test
    void cacheMiss_resolvesRepositoryShapeResponse_andPopulatesCache() {
        lastResponseBody = """
                {"data":{"node":{"name":"widgets","owner":{"login":"acme"}}}}
                """;

        Optional<OwnerRepo> result =
                new GitHubNodeIdResolver(cache).resolve(provider, new MutationNodeIdRef("R_1", null), "token");

        assertEquals(Optional.of(new OwnerRepo("acme", "widgets")), result);
        assertEquals(Optional.of(new OwnerRepo("acme", "widgets")), cache.lookup("github", "R_1"));
    }

    @Test
    void cacheMiss_resolvesIssueShapeResponse_nestedUnderRepository() {
        lastResponseBody = """
                {"data":{"node":{"repository":{"name":"widgets","owner":{"login":"acme"}}}}}
                """;

        Optional<OwnerRepo> result =
                new GitHubNodeIdResolver(cache).resolve(provider, new MutationNodeIdRef("I_1", null), "token");

        assertEquals(Optional.of(new OwnerRepo("acme", "widgets")), result);
    }

    @Test
    void malformedResponse_returnsEmpty_andDoesNotPopulateCache() {
        lastResponseBody = "not json";

        Optional<OwnerRepo> result =
                new GitHubNodeIdResolver(cache).resolve(provider, new MutationNodeIdRef("R_1", null), "token");

        assertTrue(result.isEmpty());
        assertTrue(cache.lookup("github", "R_1").isEmpty());
    }

    @Test
    void nullNode_returnsEmpty() {
        lastResponseBody = """
                {"data":{"node":null}}
                """;

        Optional<OwnerRepo> result =
                new GitHubNodeIdResolver(cache).resolve(provider, new MutationNodeIdRef("R_1", null), "token");

        assertTrue(result.isEmpty());
    }

    /**
     * fogwall originates this lookup rather than brokering it, so it identifies itself. Presenting the caller's CLI
     * would attribute to that CLI a request it never sent; presenting the HTTP client library's default would name the
     * library and the Java runtime.
     */
    @Test
    void identifiesItselfOnItsOwnLookup() {
        lastResponseBody = """
                {"data":{"node":{"name":"widgets","owner":{"login":"acme"}}}}
                """;

        new GitHubNodeIdResolver(cache).resolve(provider, new MutationNodeIdRef("R_1", null), "token");

        assertEquals(List.of("fogwall"), seenUserAgents);
    }

    private static final class InMemoryGitHubNodeIdCache implements GitHubNodeIdCache {
        private final Map<String, OwnerRepo> entries = new HashMap<>();

        @Override
        public Optional<OwnerRepo> lookup(String provider, String nodeId) {
            return Optional.ofNullable(entries.get(provider + ":" + nodeId));
        }

        @Override
        public void store(String provider, String nodeId, OwnerRepo ownerRepo) {
            entries.put(provider + ":" + nodeId, ownerRepo);
        }
    }
}
