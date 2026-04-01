package org.finos.gitproxy.e2e;

import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jgit.http.server.GitServlet;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.PushStoreFactory;
import org.finos.gitproxy.git.*;
import org.finos.gitproxy.provider.GenericProxyProvider;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;

/**
 * Starts and stops a real Jetty server wired up identically to {@code GitProxyJettyApplication} but with a single
 * {@link GenericProxyProvider} pointing at the test Gitea instance, listening on an ephemeral port.
 *
 * <p>Intended for use inside {@code @Tag("e2e")} tests as a JUnit {@code @BeforeAll} / {@code @AfterAll} resource.
 */
class JettyProxyFixture implements AutoCloseable {

    private static final String PUSH_PREFIX = "/push";
    private static final String PROXY_PREFIX = "/proxy";

    private final Server server;
    private final int port;
    private final PushStore pushStore;

    JettyProxyFixture(URI giteaUri) throws Exception {
        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(0); // ephemeral
        server.addConnector(connector);

        pushStore = PushStoreFactory.inMemory();
        var storeForwardCache = new LocalRepositoryCache(Files.createTempDirectory("jgit-proxy-e2e-sf-"), 0, true);
        var proxyCache = new LocalRepositoryCache();

        var provider = GenericProxyProvider.builder()
                .name("gitea-e2e")
                .uri(giteaUri)
                .basePath("")
                .customPath(null)
                .build();

        var commitConfig = buildCommitConfig();
        var context = new ServletContextHandler("/", false, false);

        // Store-and-forward GitServlet on /push/...
        var resolver = new StoreAndForwardRepositoryResolver(storeForwardCache, provider);
        var gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver(resolver);
        var approvalGateway = new AutoApproveGateway(pushStore);
        gitServlet.setReceivePackFactory(
                new StoreAndForwardReceivePackFactory(provider, commitConfig, pushStore, approvalGateway));
        gitServlet.setUploadPackFactory(new StoreAndForwardUploadPackFactory());

        String pushServletPath = PUSH_PREFIX + provider.servletPath();
        String pushMapping = pushServletPath + "/*";
        var gitHolder = new ServletHolder(gitServlet);
        gitHolder.setName("git-gitea-e2e");
        context.addServlet(gitHolder, pushMapping);
        context.addFilter(
                new FilterHolder(new SmartHttpErrorFilter()), pushMapping, EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(
                new FilterHolder(new BasicAuthChallengeFilter()), pushMapping, EnumSet.of(DispatcherType.REQUEST));

        // Transparent proxy GitProxyServlet on /proxy/...
        String proxyServletPath = PROXY_PREFIX + provider.servletPath();
        String proxyMapping = proxyServletPath + "/*";

        var proxyServlet = new GitProxyServlet();
        var proxyHolder = new ServletHolder(proxyServlet);
        proxyHolder.setName("proxy-gitea-e2e");
        proxyHolder.setInitParameter("proxyTo", giteaUri.toString());
        proxyHolder.setInitParameter("prefix", proxyServletPath);
        proxyHolder.setInitParameter("hostHeader", giteaUri.getHost());
        proxyHolder.setInitParameter("preserveHost", "false");
        context.addServlet(proxyHolder, proxyMapping);

        // Proxy-mode filter chain — mirrors GitProxyServletRegistrar.registerFilters()
        String serviceUrl = "http://localhost";
        addFilter(context, proxyMapping, new PushStoreAuditFilter(pushStore));
        addFilter(context, proxyMapping, new ForceGitClientFilter());
        addFilter(context, proxyMapping, new ParseGitRequestFilter(provider, PROXY_PREFIX));
        addFilter(context, proxyMapping, new EnrichPushCommitsFilter(provider, proxyCache, PROXY_PREFIX));
        addFilter(context, proxyMapping, new AllowApprovedPushFilter(pushStore, serviceUrl));
        addFilter(context, proxyMapping, new CheckAuthorEmailsFilter(commitConfig));
        addFilter(context, proxyMapping, new CheckCommitMessagesFilter(commitConfig));
        addFilter(context, proxyMapping, new ScanDiffFilter(provider, commitConfig, proxyCache));
        addFilter(context, proxyMapping, new GpgSignatureFilter(GpgConfig.defaultConfig()));
        addFilter(context, proxyMapping, new ValidationSummaryFilter());
        addFilter(context, proxyMapping, new FetchFinalizerFilter());
        addFilter(context, proxyMapping, new PushFinalizerFilter(serviceUrl));
        addFilter(context, proxyMapping, new AuditLogFilter());

        server.setHandler(context);
        server.start();

        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    /** The port the proxy is listening on. */
    int getPort() {
        return port;
    }

    /** The in-memory push store, exposed for approval flow in tests. */
    PushStore getPushStore() {
        return pushStore;
    }

    /** Base URL for push (store-and-forward) operations: {@code http://localhost:{port}/push/localhost}. */
    String getPushBase() {
        return "http://localhost:" + port + PUSH_PREFIX + "/localhost";
    }

    /** Base URL for proxy (transparent proxy) operations: {@code http://localhost:{port}/proxy/localhost}. */
    String getProxyBase() {
        return "http://localhost:" + port + PROXY_PREFIX + "/localhost";
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }

    private static void addFilter(ServletContextHandler ctx, String mapping, jakarta.servlet.Filter filter) {
        var holder = new FilterHolder(filter);
        holder.setAsyncSupported(true);
        ctx.addFilter(holder, mapping, EnumSet.of(DispatcherType.REQUEST));
    }

    /**
     * Commit validation config matching the shell-script test suite:
     *
     * <ul>
     *   <li>Author email domain must be one of the known test domains
     *   <li>Block {@code noreply}/{@code bot} local parts
     *   <li>Block WIP / fixup! / DO NOT MERGE messages
     *   <li>Block {@code password=} / {@code token=} secrets in messages
     * </ul>
     */
    static CommitConfig buildCommitConfig() {
        return CommitConfig.builder()
                .author(CommitConfig.AuthorConfig.builder()
                        .email(CommitConfig.EmailConfig.builder()
                                .domain(CommitConfig.DomainConfig.builder()
                                        .allow(Pattern.compile(
                                                "(proton\\.me|gmail\\.com|outlook\\.com|yahoo\\.com|example\\.com)$"))
                                        .build())
                                .local(CommitConfig.LocalConfig.builder()
                                        .block(Pattern.compile("^(noreply|no-reply|bot|nobody)$"))
                                        .build())
                                .build())
                        .build())
                .message(CommitConfig.MessageConfig.builder()
                        .block(CommitConfig.BlockConfig.builder()
                                .literals(List.of("WIP", "DO NOT MERGE", "fixup!", "squash!"))
                                .patterns(List.of(Pattern.compile("(?i)(password|secret|token)\\s*[=:]\\s*\\S+")))
                                .build())
                        .build())
                .build();
    }
}
