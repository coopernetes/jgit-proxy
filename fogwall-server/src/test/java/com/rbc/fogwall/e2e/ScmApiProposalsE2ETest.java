package com.rbc.fogwall.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.config.BlockConfig;
import com.rbc.fogwall.config.ContentPatternConfig;
import com.rbc.fogwall.config.SecretScanConfig;
import com.rbc.fogwall.db.ScmApiActionStore;
import com.rbc.fogwall.db.model.MatchTarget;
import com.rbc.fogwall.db.model.MatchType;
import com.rbc.fogwall.db.model.ScmApiActionQuery;
import com.rbc.fogwall.db.model.ScmApiActionRecord;
import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.jetty.FogwallServletRegistrar;
import com.rbc.fogwall.permission.InMemoryRepoPermissionStore;
import com.rbc.fogwall.permission.RepoPermission;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.ForgejoProvider;
import com.rbc.fogwall.scmapi.ProposalContent;
import com.rbc.fogwall.scmapi.ProposalContentInspector;
import com.rbc.fogwall.service.TokenPushIdentityResolver;
import com.rbc.fogwall.servlet.ScmApiRestForwardServlet;
import com.rbc.fogwall.servlet.ScmApiRestPathPolicy;
import com.rbc.fogwall.servlet.filter.ScmApiAuditFilter;
import com.rbc.fogwall.servlet.filter.ScmApiAuthenticateFilter;
import com.rbc.fogwall.servlet.filter.ScmApiContentInspectionFilter;
import com.rbc.fogwall.servlet.filter.ScmApiForgejoGateFilter;
import com.rbc.fogwall.servlet.filter.ScmApiUserAgentFilter;
import com.rbc.fogwall.user.ScmIdentity;
import com.rbc.fogwall.user.StaticUserStore;
import com.rbc.fogwall.user.UserEntry;
import com.rbc.fogwall.validation.SecretScanCheck;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.*;

/**
 * End-to-end tests for the proposals surface against a real Gitea, exercising the assembled filter chain rather than
 * each filter alone.
 *
 * <p>The bugs this surface produces are chain-assembly bugs, and unit tests pass straight through them: the allowlist
 * admitted a PATCH the forwarder then refused with 405, because {@code HttpServlet} has no {@code doPatch}; the
 * encoded-separator hardening broke the blob fetch {@code fj} makes before creating a pull request. Neither is visible
 * from a filter tested on its own — only from a request that traverses the whole chain and reaches a real upstream.
 *
 * <p>Gitea is the provider under test because it is the one with a container, and because its dialect drives the REST
 * forwarder that GitLab shares.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScmApiProposalsE2ETest {

    private static final String PROVIDER = "gitea";
    private static final String CONNECTOR = "scm-api-gitea";
    private static final String PROXY_USER = "devuser";
    private static final String BLOCKED_TERM = "internal.corp.example.com";
    /** Synthetic but Luhn-valid, with the context keyword the bundle requires. */
    private static final String PII_TEXT = "employee sin: 123 456 782";

    private static GiteaContainer gitea;
    private static Server server;
    private static String token;
    private static int port;
    private static RecordingActionStore actionStore;
    private static HttpClient client;

    /** Captures what the audit filter wrote, so the assertions can read fogwall's own account of each request. */
    private static final class RecordingActionStore implements ScmApiActionStore {
        private final List<ScmApiActionRecord> saved = new CopyOnWriteArrayList<>();

        @Override
        public void save(ScmApiActionRecord record) {
            saved.add(record);
        }

        @Override
        public Optional<ScmApiActionRecord> findById(String id) {
            return saved.stream().filter(r -> id.equals(r.getId())).findFirst();
        }

        @Override
        public List<ScmApiActionRecord> find(ScmApiActionQuery query) {
            return List.copyOf(saved);
        }

        @Override
        public void initialize() {}

        void clear() {
            saved.clear();
        }

        /** The single record for the request just made — asserting on "the last one written" reads across tests. */
        ScmApiActionRecord only() {
            assertEquals(1, saved.size(), () -> "expected exactly one audit record, got " + saved);
            return saved.get(0);
        }
    }

    @BeforeAll
    static void startInfrastructure() throws Exception {
        gitea = new GiteaContainer();
        gitea.start();
        gitea.createAdminUser();
        gitea.createTestRepo();
        gitea.createTestUser();
        gitea.addTestUserAsCollaborator();
        token = gitea.generateProposalsToken();

        var provider = ForgejoProvider.builder()
                .name(PROVIDER)
                .uri(URI.create(gitea.getBaseUrl()))
                .build();

        // The proxy user is reached from the Gitea login the token resolves to, exactly as in production.
        var userStore = new StaticUserStore(List.of(UserEntry.builder()
                .username(PROXY_USER)
                .emails(List.of(GiteaContainer.TEST_USER_EMAIL))
                .scmIdentities(List.of(ScmIdentity.builder()
                        .provider(PROVIDER)
                        .username(GiteaContainer.TEST_USER)
                        .build()))
                .build()));

        var permissionStore = new InMemoryRepoPermissionStore();
        permissionStore.save(RepoPermission.builder()
                .username(PROXY_USER)
                .provider(PROVIDER)
                .target(MatchTarget.SLUG)
                .value(".*")
                .matchType(MatchType.REGEX)
                .grant(RepoPermission.Grant.PROPOSE)
                .build());
        var permissionService = new RepoPermissionService(permissionStore);

        var block = BlockConfig.builder()
                .literals(List.of(BLOCKED_TERM))
                .patterns(List.of())
                .build();
        var secretScan = SecretScanConfig.builder().enabled(false).build();
        var contentPatterns = ContentPatternConfig.builder()
                .enabled(true)
                .bundles(List.of("national-id-ca"))
                .build();
        var inspector = new ProposalContentInspector(
                () -> block, () -> secretScan, new SecretScanCheck(secretScan), () -> contentPatterns);

        actionStore = new RecordingActionStore();

        server = new Server();
        var connector = new ServerConnector(
                server, new HttpConnectionFactory(FogwallServletRegistrar.scmApiHttpConfiguration(true)));
        connector.setName(CONNECTOR);
        connector.setPort(0);
        server.addConnector(connector);

        // Built through the production factory, encoded separators allowed, so the mount matches what fogwall serves.
        var context = FogwallServletRegistrar.scmApiContext(CONNECTOR, true);
        addFilter(context, new ScmApiAuditFilter(actionStore));
        addFilter(context, new ScmApiAuthenticateFilter(provider, new TokenPushIdentityResolver(userStore)));
        addFilter(context, new ScmApiUserAgentFilter(false));
        addFilter(context, new ScmApiForgejoGateFilter(provider, permissionService));
        addFilter(
                context,
                new ScmApiContentInspectionFilter(inspector, ProposalContent::fromForgejoBody, body -> List.of()));
        context.addServlet(
                new ServletHolder(new ScmApiRestForwardServlet(
                        gitea.getBaseUrl() + "/api/v1", ScmApiRestPathPolicy.EncodedSeparators.FORGEJO_FILE_PATH)),
                "/api/v1/*");

        var contexts = new ContextHandlerCollection();
        contexts.addHandler(context);
        server.setHandler(contexts);
        server.start();
        port = connector.getLocalPort();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (server != null) server.stop();
        if (gitea != null) gitea.stop();
    }

    private static void addFilter(ServletContextHandler context, Filter filter) {
        context.addFilter(new FilterHolder(filter), "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "token " + token)
                .header("Content-Type", "application/json")
                .header("User-Agent", "tea/0.15.1");
    }

    private static HttpResponse<String> send(HttpRequest req) throws Exception {
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String repoPath() {
        return "/api/v1/repos/" + GiteaContainer.TEST_ORG + "/" + GiteaContainer.TEST_REPO;
    }

    private static void createBranch(String name) throws Exception {
        // Straight to Gitea: the branch is a precondition, not part of what the proxy is being tested on.
        var body = "{\"new_branch_name\":\"" + name + "\",\"old_branch_name\":\"main\"}";
        var direct = HttpRequest.newBuilder(URI.create(gitea.getBaseUrl() + "/api/v1/repos/" + GiteaContainer.TEST_ORG
                        + "/" + GiteaContainer.TEST_REPO + "/branches"))
                .header("Authorization", "token " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        int status = send(direct).statusCode();
        assertTrue(status == 201 || status == 409, "branch setup failed with " + status);
    }

    @Test
    @Order(1)
    void createsAPullRequestUpstreamThroughTheWholeChain() throws Exception {
        actionStore.clear();
        createBranch("e2e-create");
        var body = "{\"title\":\"e2e create\",\"body\":\"An ordinary description.\","
                + "\"head\":\"e2e-create\",\"base\":\"main\"}";
        var response = send(request(repoPath() + "/pulls")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"number\""), "upstream returned the created pull request");

        var record = actionStore.only();
        assertEquals(ScmApiActionStatus.FORWARDED, record.getStatus());
        assertEquals(PROXY_USER, record.getResolvedUser());
        assertEquals(GiteaContainer.TEST_ORG, record.getRepoOwner());
        assertEquals("pulls.create", record.getMutationField());
    }

    /**
     * The PATCH regression. {@code HttpServlet} answers 405 for a method it has no {@code doX} for, so every
     * Gitea/Forgejo update and close was allowlisted and then refused by the forwarder — invisible to a filter test,
     * and hidden on GitLab, whose updates are PUT.
     */
    @Test
    @Order(2)
    void forwardsPatchSoAPullRequestCanBeClosed() throws Exception {
        actionStore.clear();
        var response = send(request(repoPath() + "/pulls/1")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"state\":\"closed\"}"))
                .build());

        assertEquals(201, response.statusCode(), () -> "PATCH must reach the upstream, got " + response.body());
        assertTrue(response.body().contains("\"state\":\"closed\""), response.body());
        assertEquals(ScmApiActionStatus.FORWARDED, actionStore.only().getStatus());
    }

    /**
     * The blob fetch {@code fj} makes before creating a pull request. Gitea encodes a repository-relative path into one
     * segment, so refusing every encoded separator broke {@code fj pr create} outright.
     */
    @Test
    @Order(3)
    void admitsAnEncodedSeparatorInABlobPathButNotInTheRepoSegments() throws Exception {
        var template = send(request(repoPath() + "/raw/.forgejo%2Fpull_request_template.md")
                .GET()
                .build());
        assertNotEquals(400, template.statusCode(), "the file path may carry an encoded separator");

        // The owner segment is where the authorization decision is read from, so it may not.
        var smuggled = send(
                request("/api/v1/repos/" + GiteaContainer.TEST_ORG + "%2Fdecoy/" + GiteaContainer.TEST_REPO + "/pulls")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"t\",\"head\":\"x\",\"base\":\"main\"}"))
                        .build());
        assertTrue(
                smuggled.statusCode() >= 400,
                () -> "an encoded separator in the owner segment must be refused, got " + smuggled.statusCode());
    }

    @Test
    @Order(4)
    void refusesAnEndpointThatIsNotAllowlisted() throws Exception {
        actionStore.clear();
        var response = send(request(repoPath() + "/pulls/1/reviews")
                .POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"APPROVE\"}"))
                .build());

        assertEquals(403, response.statusCode(), response.body());
        assertEquals(ScmApiActionStatus.DENIED, actionStore.only().getStatus());
    }

    @Test
    @Order(5)
    void refusesABlockedTermInAProposalBody() throws Exception {
        actionStore.clear();
        createBranch("e2e-blocked");
        var body = "{\"title\":\"e2e blocked\",\"body\":\"See " + BLOCKED_TERM + " for details.\","
                + "\"head\":\"e2e-blocked\",\"base\":\"main\"}";
        var response = send(request(repoPath() + "/pulls")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertEquals(403, response.statusCode(), response.body());
        var record = actionStore.only();
        assertEquals(ScmApiActionStatus.REJECTED, record.getStatus());
        assertNull(record.getVariablesJson(), "the refused text must not be kept on the record");
    }

    /** PII blocks here rather than warning: there is no reviewer, and the text would already be published upstream. */
    @Test
    @Order(6)
    void refusesAContentPatternMatchInAProposalBody() throws Exception {
        actionStore.clear();
        createBranch("e2e-pii");
        var body = "{\"title\":\"e2e pii\",\"body\":\"Reporter left their " + PII_TEXT + " in the dump.\","
                + "\"head\":\"e2e-pii\",\"base\":\"main\"}";
        var response = send(request(repoPath() + "/pulls")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("Social Insurance Number"), response.body());
        assertFalse(response.body().contains("123 456 782"), "the refusal must not repeat the matched value");
        assertEquals(ScmApiActionStatus.REJECTED, actionStore.only().getStatus());
    }

    /**
     * A write carrying a query parameter is not a shape any supported CLI produces, so it is refused before the content
     * it carries matters. This is the same request that would otherwise smuggle a description past body-only
     * inspection.
     */
    @Test
    @Order(7)
    void refusesAWriteCarryingAQueryParameter() throws Exception {
        createBranch("e2e-query");
        var query = "?body=" + URLEncoder.encode(PII_TEXT, StandardCharsets.UTF_8);
        var body = "{\"title\":\"e2e query\",\"body\":\"clean\",\"head\":\"e2e-query\",\"base\":\"main\"}";
        var response = send(request(repoPath() + "/pulls" + query)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    @Order(8)
    void forwardsAReadCarryingPermittedFilterParameters() throws Exception {
        var response = send(
                request(repoPath() + "/issues?state=open&page=1&limit=10").GET().build());
        assertEquals(200, response.statusCode(), response.body());
    }

    /** A credential in the query string would have the upstream act as someone other than the audited caller. */
    @Test
    @Order(9)
    void refusesAReadCarryingACredentialParameter() throws Exception {
        var response = send(request(repoPath() + "/issues?state=open&access_token=someoneelse")
                .GET()
                .build());
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    @Order(10)
    void refusesACallerWhoseTokenResolvesToNoProxyUser() throws Exception {
        var response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + repoPath() + "/pulls"))
                .header("Authorization", "token " + gitea.generateAdminToken())
                .header("Content-Type", "application/json")
                .header("User-Agent", "tea/0.15.1")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"title\":\"t\",\"head\":\"e2e-create\",\"base\":\"main\"}"))
                .build());

        assertEquals(401, response.statusCode(), response.body());
    }
}
