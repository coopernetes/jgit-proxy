package org.finos.gitproxy.jetty;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jgit.http.server.GitServlet;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.git.*;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.DummyUserAuthorizationService;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;

/**
 * Utility class that registers the git proxy servlets and filters onto a Jetty {@link ServletContextHandler}. Shared
 * between the standalone server ({@link GitProxyJettyApplication}) and the server-with-dashboard application in
 * {@code jgit-proxy-api}.
 */
@Slf4j
public final class GitProxyServletRegistrar {

    public static final String PUSH_PATH_PREFIX = "/push";
    public static final String PROXY_PATH_PREFIX = "/proxy";

    private GitProxyServletRegistrar() {}

    public static void registerGitServlet(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache cache,
            CommitConfig commitConfig,
            PushStore pushStore) {
        registerGitServlet(context, provider, cache, commitConfig, pushStore, null);
    }

    public static void registerGitServlet(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache cache,
            CommitConfig commitConfig,
            PushStore pushStore,
            String serviceUrl) {
        var resolver = new StoreAndForwardRepositoryResolver(cache, provider);
        var approvalGateway = new UiApprovalGateway(pushStore);

        var gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver(resolver);
        gitServlet.setReceivePackFactory(
                new StoreAndForwardReceivePackFactory(provider, commitConfig, pushStore, approvalGateway, serviceUrl));
        gitServlet.setUploadPackFactory(new StoreAndForwardUploadPackFactory());

        String pushPath = PUSH_PATH_PREFIX + provider.servletPath();
        String pushMapping = pushPath + "/*";

        var holder = new ServletHolder(gitServlet);
        holder.setName("git-" + provider.getName());
        context.addServlet(holder, pushMapping);

        var errorFilter = new FilterHolder(new SmartHttpErrorFilter());
        context.addFilter(errorFilter, pushMapping, EnumSet.of(DispatcherType.REQUEST));

        var authFilter = new FilterHolder(new BasicAuthChallengeFilter());
        context.addFilter(authFilter, pushMapping, EnumSet.of(DispatcherType.REQUEST));

        log.info("Registered GitServlet for {} at {}", provider.getName(), pushMapping);
    }

    public static void registerProxyServlet(ServletContextHandler context, GitProxyProvider provider) {
        String proxyPath = PROXY_PATH_PREFIX + provider.servletPath();
        String proxyMapping = proxyPath + "/*";

        var proxyServlet = new GitProxyServlet();
        var proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setName("proxy-" + provider.getName());
        proxyServletHolder.setInitParameter("proxyTo", provider.getUri().toString());
        proxyServletHolder.setInitParameter("prefix", proxyPath);
        proxyServletHolder.setInitParameter("hostHeader", provider.getUri().getHost());
        proxyServletHolder.setInitParameter("preserveHost", "false");
        context.addServlet(proxyServletHolder, proxyMapping);

        log.info("Registered proxy servlet for {} at {}", provider.getName(), proxyMapping);
    }

    public static void registerFilters(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache repositoryCache,
            JettyConfigurationBuilder configBuilder,
            CommitConfig commitConfig,
            PushStore pushStore) {
        registerFilters(context, provider, repositoryCache, configBuilder, commitConfig, pushStore, null);
    }

    public static void registerFilters(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache repositoryCache,
            JettyConfigurationBuilder configBuilder,
            CommitConfig commitConfig,
            PushStore pushStore,
            String serviceUrl) {
        String urlPattern = PROXY_PATH_PREFIX + provider.servletPath() + "/*";

        var pushStoreAuditFilterHolder = new FilterHolder(new PushStoreAuditFilter(pushStore));
        pushStoreAuditFilterHolder.setAsyncSupported(true);
        context.addFilter(pushStoreAuditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var forceGitClientFilterHolder = new FilterHolder(new ForceGitClientFilter());
        forceGitClientFilterHolder.setAsyncSupported(true);
        context.addFilter(forceGitClientFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var parseRequestFilterHolder = new FilterHolder(new ParseGitRequestFilter(provider, PROXY_PATH_PREFIX));
        parseRequestFilterHolder.setAsyncSupported(true);
        context.addFilter(parseRequestFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var enrichCommitsFilterHolder =
                new FilterHolder(new EnrichPushCommitsFilter(provider, repositoryCache, PROXY_PATH_PREFIX));
        enrichCommitsFilterHolder.setAsyncSupported(true);
        context.addFilter(enrichCommitsFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Pre-approval check: must be AFTER EnrichPushCommitsFilter (which populates commitTo/branch)
        // but BEFORE content validation filters. If an approved record exists for this commitTo+branch+repo,
        // sets preApproved=true which short-circuits all content filters.
        var preApprovalHolder = new FilterHolder(new AllowApprovedPushFilter(pushStore, serviceUrl));
        preApprovalHolder.setAsyncSupported(true);
        context.addFilter(preApprovalHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        List<WhitelistByUrlFilter> whitelistFilters = configBuilder.buildWhitelistFilters(provider);
        if (!whitelistFilters.isEmpty()) {
            var whitelistAggFilterHolder =
                    new FilterHolder(new WhitelistAggregateFilter(1000, provider, whitelistFilters, PROXY_PATH_PREFIX));
            whitelistAggFilterHolder.setAsyncSupported(true);
            context.addFilter(whitelistAggFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
            log.info("Registered {} whitelist filter(s) for provider {}", whitelistFilters.size(), provider.getName());
        }

        var pushPermissionFilterHolder =
                new FilterHolder(new CheckUserPushPermissionFilter(new DummyUserAuthorizationService()));
        pushPermissionFilterHolder.setAsyncSupported(true);
        context.addFilter(pushPermissionFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var authorEmailsFilterHolder = new FilterHolder(new CheckAuthorEmailsFilter(commitConfig));
        authorEmailsFilterHolder.setAsyncSupported(true);
        context.addFilter(authorEmailsFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var commitMessagesFilterHolder = new FilterHolder(new CheckCommitMessagesFilter(commitConfig));
        commitMessagesFilterHolder.setAsyncSupported(true);
        context.addFilter(commitMessagesFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var scanDiffFilterHolder = new FilterHolder(new ScanDiffFilter(provider, commitConfig, repositoryCache));
        scanDiffFilterHolder.setAsyncSupported(true);
        context.addFilter(scanDiffFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var gpgFilterHolder = new FilterHolder(new GpgSignatureFilter(GpgConfig.defaultConfig()));
        gpgFilterHolder.setAsyncSupported(true);
        context.addFilter(gpgFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Terminal filter: collects all recorded issues and sends a combined rejection response
        var validationSummaryHolder = new FilterHolder(new ValidationSummaryFilter());
        validationSummaryHolder.setAsyncSupported(true);
        context.addFilter(validationSummaryHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        log.info("Registered content validation filters for provider {}", provider.getName());

        var fetchFinalizerHolder = new FilterHolder(new FetchFinalizerFilter());
        fetchFinalizerHolder.setAsyncSupported(true);
        context.addFilter(fetchFinalizerHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var pushFinalizerHolder = new FilterHolder(new PushFinalizerFilter(serviceUrl));
        pushFinalizerHolder.setAsyncSupported(true);
        context.addFilter(pushFinalizerHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var auditFilterHolder = new FilterHolder(new AuditLogFilter());
        auditFilterHolder.setAsyncSupported(true);
        context.addFilter(auditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
    }
}
