package org.finos.gitproxy.jetty;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jgit.http.server.GitServlet;
import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.config.GpgConfig;
import org.finos.gitproxy.db.FetchStore;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.git.*;
import org.finos.gitproxy.jetty.config.JettyConfigurationBuilder;
import org.finos.gitproxy.jetty.reload.ConfigHolder;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.provider.BitbucketProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;

/**
 * Utility class that registers the git proxy servlets and filters onto a Jetty {@link ServletContextHandler}. Shared
 * between the standalone server ({@link GitProxyJettyApplication}) and the server-with-dashboard application in
 * {@code git-proxy-java-dashboard}.
 */
@Slf4j
public final class GitProxyServletRegistrar {

    public static final String PUSH_PATH_PREFIX = "/push";
    public static final String PROXY_PATH_PREFIX = "/proxy";

    private GitProxyServletRegistrar() {}

    /**
     * Registers git servlets, proxy servlets, and filter chains for every provider. This is the primary entry point for
     * both the standalone and dashboard applications.
     */
    public static void registerProviders(
            ServletContextHandler context,
            GitProxyContext gitProxyCtx,
            JettyConfigurationBuilder configBuilder,
            List<GitProxyProvider> providers) {
        // Wire up JGit's HTTP transport factory once for all store-and-forward connections
        if (gitProxyCtx.upstreamTls() != null) {
            org.eclipse.jgit.transport.HttpTransport.setConnectionFactory(
                    new org.finos.gitproxy.tls.SslAwareHttpConnectionFactory(
                            gitProxyCtx.upstreamTls().trustManagers()));
            log.info("Custom upstream SSL trust applied to JGit HTTP transport");
        }
        // ForceGitClientFilter is registered once at the top-level proxy and push paths so it covers
        // any path with the right prefix, including paths that don't match a configured provider.
        var forceGitClientHolder = new FilterHolder(new ForceGitClientFilter());
        forceGitClientHolder.setAsyncSupported(true);
        context.addFilter(forceGitClientHolder, PROXY_PATH_PREFIX + "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(forceGitClientHolder, PUSH_PATH_PREFIX + "/*", EnumSet.of(DispatcherType.REQUEST));

        ConfigHolder configHolder = configBuilder.buildConfigHolder();
        Supplier<CommitConfig> commitConfigSupplier = configHolder::getCommitConfig;

        for (GitProxyProvider provider : providers) {
            log.info("Registering provider: {}", provider.getName());
            List<UrlRuleFilter> urlRuleFilters = configBuilder.buildUrlRuleFilters(provider);
            registerGitServlet(
                    context,
                    provider,
                    gitProxyCtx.storeForwardCache(),
                    commitConfigSupplier,
                    gitProxyCtx.pushStore(),
                    gitProxyCtx.serviceUrl(),
                    gitProxyCtx.approvalGateway(),
                    gitProxyCtx.pushIdentityResolver(),
                    gitProxyCtx.repoPermissionService(),
                    gitProxyCtx.heartbeatIntervalSeconds(),
                    gitProxyCtx.failFast(),
                    gitProxyCtx.upstreamConnectTimeoutSeconds(),
                    urlRuleFilters,
                    gitProxyCtx.repoRegistry());
            registerProxyServlet(
                    context,
                    provider,
                    gitProxyCtx.pushStore(),
                    gitProxyCtx.proxyConnectTimeoutSeconds(),
                    gitProxyCtx.upstreamTls());
            registerCoreFilters(
                    context,
                    provider,
                    gitProxyCtx.proxyCache(),
                    configBuilder,
                    commitConfigSupplier,
                    gitProxyCtx.pushStore(),
                    gitProxyCtx.serviceUrl(),
                    gitProxyCtx.approvalGateway(),
                    gitProxyCtx.pushIdentityResolver(),
                    gitProxyCtx.repoPermissionService(),
                    gitProxyCtx.fetchStore(),
                    gitProxyCtx.repoRegistry());
        }
    }

    public static void registerGitServlet(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache cache,
            Supplier<CommitConfig> commitConfigSupplier,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway,
            PushIdentityResolver pushIdentityResolver,
            RepoPermissionService repoPermissionService,
            int heartbeatIntervalSeconds,
            boolean failFast,
            int connectTimeoutSeconds,
            List<UrlRuleFilter> urlRuleFilters,
            RepoRegistry repoRegistry) {
        var resolver = new StoreAndForwardRepositoryResolver(cache, provider);

        var factory = new StoreAndForwardReceivePackFactory(
                provider,
                commitConfigSupplier,
                GpgConfig.defaultConfig(),
                repoPermissionService,
                pushIdentityResolver,
                pushStore,
                approvalGateway,
                serviceUrl,
                Duration.ofSeconds(heartbeatIntervalSeconds),
                urlRuleFilters,
                repoRegistry);
        factory.setFailFast(failFast);
        factory.setConnectTimeoutSeconds(connectTimeoutSeconds);

        var gitServlet = new GitServlet();
        gitServlet.setRepositoryResolver(resolver);
        gitServlet.setReceivePackFactory(factory);
        gitServlet.setUploadPackFactory(new StoreAndForwardUploadPackFactory());

        String pushPath = PUSH_PATH_PREFIX + provider.servletPath();
        String pushMapping = pushPath + "/*";

        var holder = new ServletHolder(gitServlet);
        holder.setName("git-" + provider.getName());
        context.addServlet(holder, pushMapping);

        context.addFilter(
                new FilterHolder(new SmartHttpErrorFilter()), pushMapping, EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(
                new FilterHolder(new BasicAuthChallengeFilter()), pushMapping, EnumSet.of(DispatcherType.REQUEST));

        log.info("Registered GitServlet for {} at {}", provider.getName(), pushMapping);
    }

    public static void registerProxyServlet(
            ServletContextHandler context,
            GitProxyProvider provider,
            PushStore pushStore,
            int connectTimeoutSeconds,
            org.finos.gitproxy.tls.SslUtil.UpstreamTls upstreamTls) {
        String proxyPath = PROXY_PATH_PREFIX + provider.servletPath();
        String proxyMapping = proxyPath + "/*";

        var proxyServlet = new GitProxyServlet(pushStore, upstreamTls != null ? upstreamTls.sslContext() : null);
        var proxyHolder = new ServletHolder(proxyServlet);
        proxyHolder.setName("proxy-" + provider.getName());
        proxyHolder.setInitParameter("proxyTo", provider.getUri().toString());
        proxyHolder.setInitParameter("prefix", proxyPath);
        proxyHolder.setInitParameter("hostHeader", provider.getUri().getHost());
        proxyHolder.setInitParameter("preserveHost", "false");
        if (connectTimeoutSeconds > 0) {
            proxyHolder.setInitParameter("connectTimeout", String.valueOf(connectTimeoutSeconds * 1000L));
        }
        context.addServlet(proxyHolder, proxyMapping);

        log.info("Registered proxy servlet for {} at {}", provider.getName(), proxyMapping);
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
            Supplier<CommitConfig> commitConfigSupplier,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway,
            PushIdentityResolver pushIdentityResolver,
            RepoPermissionService repoPermissionService,
            FetchStore fetchStore,
            RepoRegistry repoRegistry) {
        String urlPattern = PROXY_PATH_PREFIX + provider.servletPath() + "/*";

        // PushStoreAuditFilter wraps the entire chain via try-finally; must be registered first.
        var pushStoreAuditFilterHolder = new FilterHolder(new PushStoreAuditFilter(pushStore));
        pushStoreAuditFilterHolder.setAsyncSupported(true);
        context.addFilter(pushStoreAuditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        // Build the orderable filter list. Sorted by getOrder() before registration so the Jetty chain
        // execution order matches the documented order ranges in GitProxyFilter.
        List<GitProxyFilter> filters = new ArrayList<>();
        filters.add(new ParseGitRequestFilter(provider, PROXY_PATH_PREFIX));
        filters.add(new EnrichPushCommitsFilter(provider, repositoryCache, PROXY_PATH_PREFIX));
        filters.add(new AllowApprovedPushFilter(pushStore, serviceUrl));

        List<UrlRuleFilter> urlRuleFilters = configBuilder.buildUrlRuleFilters(provider);
        filters.add(
                new UrlRuleAggregateFilter(100, provider, urlRuleFilters, PROXY_PATH_PREFIX, fetchStore, repoRegistry));
        long allowCount = urlRuleFilters.stream()
                .filter(f -> f.getAccess() == org.finos.gitproxy.db.model.AccessRule.Access.ALLOW)
                .count();
        long denyCount = urlRuleFilters.size() - allowCount;
        log.info(
                "Registered {} YAML allow rule(s) and {} YAML deny rule(s) for provider {} (DB rules evaluated dynamically)",
                allowCount,
                denyCount,
                provider.getName());

        if (provider instanceof BitbucketProvider bitbucketProvider) {
            filters.add(new BitbucketIdentityFilter(bitbucketProvider));
        }
        filters.add(new CheckUserPushPermissionFilter(pushIdentityResolver, repoPermissionService));
        filters.add(new IdentityVerificationFilter(pushIdentityResolver, commitConfigSupplier));
        filters.add(new CheckEmptyBranchFilter());
        filters.add(new CheckHiddenCommitsFilter(provider));
        filters.add(new CheckAuthorEmailsFilter(commitConfigSupplier));
        filters.add(new CheckCommitMessagesFilter(commitConfigSupplier));
        filters.add(new ScanDiffFilter(provider, commitConfigSupplier));
        filters.add(new SecretScanningFilter(commitConfigSupplier));
        filters.add(new GpgSignatureFilter(GpgConfig.defaultConfig()));
        filters.add(new ValidationSummaryFilter());
        filters.add(new FetchFinalizerFilter());
        filters.add(new PushFinalizerFilter(serviceUrl, approvalGateway));
        filters.add(new AuditLogFilter());

        boolean failFast = configBuilder != null && configBuilder.isFailFast();
        if (failFast) {
            filters.forEach(f -> {
                if (f instanceof AbstractGitProxyFilter af) af.setFailFast(true);
            });
        }

        filters.sort(Comparator.comparingInt(GitProxyFilter::getOrder));

        for (GitProxyFilter filter : filters) {
            var holder = new FilterHolder(filter);
            holder.setAsyncSupported(true);
            context.addFilter(holder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
        }

        log.info("Registered {} proxy filters for provider {}", filters.size(), provider.getName());
    }
}
