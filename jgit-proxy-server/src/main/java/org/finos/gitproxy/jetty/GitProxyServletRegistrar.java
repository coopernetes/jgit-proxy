package org.finos.gitproxy.jetty;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jgit.http.server.GitServlet;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.approval.AutoApprovalGateway;
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
 * {@code jgit-proxy-dashboard}.
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
        registerGitServlet(
                context, provider, cache, commitConfig, pushStore, null, new AutoApprovalGateway(pushStore), 10);
    }

    public static void registerGitServlet(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache cache,
            CommitConfig commitConfig,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway) {
        registerGitServlet(context, provider, cache, commitConfig, pushStore, serviceUrl, approvalGateway, 10);
    }

    public static void registerGitServlet(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache cache,
            CommitConfig commitConfig,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway,
            int heartbeatIntervalSeconds) {
        var resolver = new StoreAndForwardRepositoryResolver(cache, provider);

        var gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver(resolver);
        gitServlet.setReceivePackFactory(new StoreAndForwardReceivePackFactory(
                provider,
                commitConfig,
                GpgConfig.defaultConfig(),
                new DummyUserAuthorizationService(),
                pushStore,
                approvalGateway,
                serviceUrl,
                Duration.ofSeconds(heartbeatIntervalSeconds)));
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
        registerCoreFilters(
                context,
                provider,
                repositoryCache,
                configBuilder,
                commitConfig,
                pushStore,
                null,
                new AutoApprovalGateway(pushStore));
    }

    public static void registerFilters(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache repositoryCache,
            JettyConfigurationBuilder configBuilder,
            CommitConfig commitConfig,
            PushStore pushStore,
            String serviceUrl) {
        registerCoreFilters(
                context,
                provider,
                repositoryCache,
                configBuilder,
                commitConfig,
                pushStore,
                serviceUrl,
                new AutoApprovalGateway(pushStore));
    }

    public static void registerFilters(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache repositoryCache,
            JettyConfigurationBuilder configBuilder,
            CommitConfig commitConfig,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway) {
        registerCoreFilters(
                context,
                provider,
                repositoryCache,
                configBuilder,
                commitConfig,
                pushStore,
                serviceUrl,
                approvalGateway);
    }

    /**
     * Registers the core proxy filter chain for the given provider. Covers all content validation including
     * {@link AllowApprovedPushFilter}, which is harmless in standalone mode because no push records are ever set to
     * {@code APPROVED} via the transparent-proxy re-push flow when running without a dashboard.
     */
    public static void registerCoreFilters(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache repositoryCache,
            JettyConfigurationBuilder configBuilder,
            CommitConfig commitConfig,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway) {
        String urlPattern = PROXY_PATH_PREFIX + provider.servletPath() + "/*";

        // PushStoreAuditFilter wraps the entire chain via try-finally; must be registered first.
        var pushStoreAuditFilterHolder = new FilterHolder(new PushStoreAuditFilter(pushStore));
        pushStoreAuditFilterHolder.setAsyncSupported(true);
        context.addFilter(pushStoreAuditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Build the orderable filter list. Sorted by getOrder() before registration so the Jetty chain
        // execution order matches the documented order ranges in GitProxyFilter.
        List<GitProxyFilter> filters = new ArrayList<>();
        filters.add(new ForceGitClientFilter());
        filters.add(new ParseGitRequestFilter(provider, PROXY_PATH_PREFIX));
        filters.add(new EnrichPushCommitsFilter(provider, repositoryCache, PROXY_PATH_PREFIX));
        filters.add(new AllowApprovedPushFilter(pushStore, serviceUrl));

        List<WhitelistByUrlFilter> whitelistFilters = configBuilder.buildWhitelistFilters(provider);
        if (!whitelistFilters.isEmpty()) {
            filters.add(new WhitelistAggregateFilter(100, provider, whitelistFilters, PROXY_PATH_PREFIX));
            log.info("Registered {} whitelist filter(s) for provider {}", whitelistFilters.size(), provider.getName());
        }

        filters.add(new CheckUserPushPermissionFilter(new DummyUserAuthorizationService()));
        filters.add(new CheckEmptyBranchFilter());
        filters.add(new CheckHiddenCommitsFilter(provider));
        filters.add(new CheckAuthorEmailsFilter(commitConfig));
        filters.add(new CheckCommitMessagesFilter(commitConfig));
        filters.add(new ScanDiffFilter(provider, commitConfig));
        filters.add(new SecretScanningFilter(commitConfig.getSecretScanning()));
        filters.add(new GpgSignatureFilter(GpgConfig.defaultConfig()));
        filters.add(new ValidationSummaryFilter());
        filters.add(new FetchFinalizerFilter());
        filters.add(new PushFinalizerFilter(serviceUrl, approvalGateway));
        filters.add(new AuditLogFilter());

        filters.sort(Comparator.comparingInt(GitProxyFilter::getOrder));

        for (GitProxyFilter filter : filters) {
            var holder = new FilterHolder(filter);
            holder.setAsyncSupported(true);
            context.addFilter(holder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
        }

        log.info("Registered {} proxy filters for provider {}", filters.size(), provider.getName());
    }
}
