package com.rbc.fogwall.jetty;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.servlet.ScmApiRestPath;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the mechanism the SCM API proxy's mounting depends on: a {@link ServletContextHandler} at {@code "/"} bound to
 * one named {@link ServerConnector} via Jetty's {@code "@connectorName"} virtual-host form, so each provider's dialect
 * owns the root of its own port.
 *
 * <p>This is what makes {@code gh} and {@code fj} usable at all — both address the API from the host root and discard
 * any path prefix, so a shared {@code /scm-api/<provider>} mount is unreachable for them. It also keeps two providers
 * of the same platform from colliding, since every GitLab claims {@code /api/v4} and every Gitea/Forgejo
 * {@code /api/v1}.
 *
 * <p>If Jetty ever changed this routing, the dialects would silently cross-wire — a GitLab request answered by the
 * Gitea pipeline — so the behaviour is asserted directly rather than assumed from the javadoc.
 */
class ScmApiConnectorRoutingTest {

    private Server server;
    private int gitlabPort;
    private int giteaPort;
    private int mainPort;

    /** Echoes which context handled the request, so the assertions can tell the pipelines apart. */
    private static class NameServlet extends HttpServlet {
        private final String name;

        NameServlet(String name) {
            this.name = name;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setStatus(200);
            resp.getWriter().write(name + ":" + req.getRequestURI());
        }
    }

    /**
     * Echoes {@link ScmApiRestPath#rawSubPath}, which is what the allowlists and the forwarder actually call. It
     * reaches {@code getServletPath()}, unlike {@link NameServlet}, and so exercises the servlet layer's own
     * ambiguous-URI rejection rather than only the connector's {@code UriCompliance}.
     */
    private static class SubPathServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setStatus(200);
            resp.getWriter().write(ScmApiRestPath.rawSubPath(req));
        }
    }

    private static ServletContextHandler contextFor(
            String connectorName, String mapping, String servletName, boolean allowEncodedSeparator) {
        // Built through the production factory so the test pins what the server actually mounts.
        var context = FogwallServletRegistrar.scmApiContext(connectorName, allowEncodedSeparator);
        context.addServlet(new ServletHolder(new NameServlet(servletName)), mapping);
        return context;
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new Server();

        var mainConnector = new ServerConnector(server);
        mainConnector.setPort(0);
        mainConnector.setName(FogwallServletRegistrar.MAIN_HTTP_CONNECTOR);
        server.addConnector(mainConnector);

        var gitlabConnector = new ServerConnector(
                server, new HttpConnectionFactory(FogwallServletRegistrar.scmApiHttpConfiguration(true)));
        gitlabConnector.setPort(0);
        gitlabConnector.setName("scm-api-gitlab");
        server.addConnector(gitlabConnector);

        var giteaConnector = new ServerConnector(
                server, new HttpConnectionFactory(FogwallServletRegistrar.scmApiHttpConfiguration(false)));
        giteaConnector.setPort(0);
        giteaConnector.setName("scm-api-gitea");
        server.addConnector(giteaConnector);

        var contexts = new ContextHandlerCollection();

        // Bound to the main connectors exactly as the applications bind it, so it answers there and nowhere else.
        var main = new ServletContextHandler("/", false, false);
        main.setVirtualHosts(FogwallServletRegistrar.MAIN_VIRTUAL_HOSTS);
        main.addServlet(new ServletHolder(new NameServlet("main")), "/proxy/*");
        contexts.addHandler(main);

        var gitlabContext = contextFor("scm-api-gitlab", "/api/v4/*", "gitlab", true);
        gitlabContext.addServlet(new ServletHolder(new SubPathServlet()), "/sub/*");
        contexts.addHandler(gitlabContext);
        contexts.addHandler(contextFor("scm-api-gitea", "/api/v1/*", "gitea", false));

        server.setHandler(contexts);
        server.start();

        mainPort = mainConnector.getLocalPort();
        gitlabPort = gitlabConnector.getLocalPort();
        giteaPort = giteaConnector.getLocalPort();
    }

    /**
     * The direction that was never asserted: a context with no virtual host matches every connector, which would have
     * put the git servlets on each proposals port. An operator exposing 9443 for {@code gh} would have been exposing
     * {@code /proxy} and {@code /server} with it — on connectors that also relax URI compliance.
     */
    @Test
    void gitServletsAreNotReachableOnAProposalsPort() throws Exception {
        assertEquals(
                200,
                get(mainPort, "/proxy/github.com/acme/widgets.git/info/refs").statusCode());
        assertEquals(
                404,
                get(gitlabPort, "/proxy/github.com/acme/widgets.git/info/refs").statusCode(),
                "the git surface must not answer on the GitLab proposals port");
        assertEquals(
                404,
                get(giteaPort, "/proxy/github.com/acme/widgets.git/info/refs").statusCode(),
                "nor on the Gitea one");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void eachDialectIsServedAtTheRootOfItsOwnPort() throws Exception {
        var gitlab = get(gitlabPort, "/api/v4/projects/acme%2Fwidgets/issues");
        assertEquals(200, gitlab.statusCode());
        assertTrue(gitlab.body().startsWith("gitlab:"), gitlab.body());

        var gitea = get(giteaPort, "/api/v1/repos/acme/widgets/issues");
        assertEquals(200, gitea.statusCode());
        assertTrue(gitea.body().startsWith("gitea:"), gitea.body());
    }

    @Test
    void aDialectIsNotReachableOnAnotherProvidersPort() throws Exception {
        // The GitLab path on the Gitea port must not reach the GitLab pipeline. This is the collision guard: two
        // providers of the same platform would otherwise both answer for the same URL.
        assertEquals(404, get(giteaPort, "/api/v4/projects/acme/issues").statusCode());
        assertEquals(404, get(gitlabPort, "/api/v1/repos/acme/widgets/issues").statusCode());
    }

    @Test
    void theScmApiDialectsAreNotExposedOnTheMainGitPort() throws Exception {
        assertEquals(404, get(mainPort, "/api/v4/projects/acme/issues").statusCode());
        assertEquals(404, get(mainPort, "/api/v1/repos/acme/widgets/issues").statusCode());

        // The encoded-slash form is refused even earlier, at 400, because the main port keeps Jetty's strict
        // UriCompliance. Either way it is never served — assert only that it isn't.
        assertNotEquals(
                200, get(mainPort, "/api/v4/projects/acme%2Fwidgets/issues").statusCode());
    }

    @Test
    void theMainContextStillServesGitTrafficOnItsOwnPort() throws Exception {
        var response = get(mainPort, "/proxy/github/acme/widgets.git/info/refs");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("main:"), response.body());
    }

    /**
     * The regression that made every {@code glab} request fail with 400: the connector's relaxed
     * {@link FogwallServletRegistrar#SCM_API_URI_COMPLIANCE} gets an encoded separator past the HTTP parser, but the
     * servlet layer rejects it a second time unless the context opts in too, handing the request a Jetty
     * {@code AmbiguousURI} wrapper whose {@code getServletPath()} throws. {@link ScmApiRestPath#rawSubPath} calls
     * exactly that, so the failure lands before any fogwall filter runs — and is invisible to a servlet that reads only
     * {@code getRequestURI()}.
     */
    @Test
    void rawSubPathResolvesForAnEncodedRepositorySegment() throws Exception {
        var response = get(gitlabPort, "/sub/projects/acme%2Fwidgets/issues");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("/projects/acme%2Fwidgets/issues", response.body());
    }

    /**
     * The encoded-separator relaxation is GitLab's alone. GitLab needs it because it addresses a project as one
     * {@code owner%2Frepo} segment; Gitea/Forgejo names owner and repo as separate plain segments and so has no
     * legitimate use for one. Granting it per-listener rather than to every SCM API port keeps the dialects that cannot
     * benefit from it on Jetty's strict default, where the parser refuses the request outright.
     */
    @Test
    void onlyTheGitLabListenerAcceptsAnEncodedSeparator() throws Exception {
        assertEquals(
                200, get(gitlabPort, "/api/v4/projects/acme%2Fwidgets/issues").statusCode());
        assertEquals(
                400,
                get(giteaPort, "/api/v1/repos/acme%2Fwidgets/issues").statusCode(),
                "Gitea's listener must reject an encoded separator at the parser");
    }

    /**
     * The raw, still-encoded request URI must survive to the servlet — the REST allowlists match on it precisely
     * because {@code getPathInfo()} would decode {@code acme%2Fwidgets} into two segments.
     */
    @Test
    void theEncodedRepositorySegmentReachesTheServletIntact() throws Exception {
        var response = get(gitlabPort, "/api/v4/projects/acme%2Fwidgets/issues");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("acme%2Fwidgets"), response.body());
    }

    /**
     * Jetty's default {@code UriCompliance} rejects an encoded {@code %2F} as an ambiguous path separator, answering
     * 400 before any fogwall code runs — which would break every {@code glab} request, since GitLab addresses a project
     * as a single {@code owner%2Frepo} segment. The main git port keeps that strict default; only the SCM API listeners
     * relax it, and only for this one violation.
     */
    @Test
    void theMainGitPortStillRejectsAnEncodedSlash() throws Exception {
        assertEquals(400, get(mainPort, "/proxy/acme%2Fwidgets/info/refs").statusCode());
    }
}
