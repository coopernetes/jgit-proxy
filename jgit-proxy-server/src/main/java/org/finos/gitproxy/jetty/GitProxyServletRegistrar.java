package org.finos.gitproxy.jetty;

import jakarta.servlet.DispatcherType;
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
        registerGitServlet(context, provider, cache, commitConfig, pushStore, null, new AutoApprovalGateway(pushStore));
    }

    public static void registerGitServlet(
            ServletContextHandler context,
            GitProxyProvider provider,
            LocalRepositoryCache cache,
            CommitConfig commitConfig,
            PushStore pushStore,
            String serviceUrl,
            ApprovalGateway approvalGateway) {
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
                serviceUrl));
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
     * Registers the core proxy filter chain for the given provider. Covers all content validation but does not include
     * the dashboard-specific {@link AllowApprovedPushFilter}. Call {@link #registerApprovalFilters} additionally when
     * running with the dashboard.
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
        // In standalone mode (no dashboard) this filter is harmless — no records are ever set to APPROVED
        // via the transparent-proxy re-push flow, so it is always a no-op.
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

        var emptyBranchFilterHolder = new FilterHolder(new CheckEmptyBranchFilter());
        emptyBranchFilterHolder.setAsyncSupported(true);
        context.addFilter(emptyBranchFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var hiddenCommitsFilterHolder = new FilterHolder(new CheckHiddenCommitsFilter(provider));
        hiddenCommitsFilterHolder.setAsyncSupported(true);
        context.addFilter(hiddenCommitsFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var authorEmailsFilterHolder = new FilterHolder(new CheckAuthorEmailsFilter(commitConfig));
        authorEmailsFilterHolder.setAsyncSupported(true);
        context.addFilter(authorEmailsFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var commitMessagesFilterHolder = new FilterHolder(new CheckCommitMessagesFilter(commitConfig));
        commitMessagesFilterHolder.setAsyncSupported(true);
        context.addFilter(commitMessagesFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var scanDiffFilterHolder = new FilterHolder(new ScanDiffFilter(provider, commitConfig));
        scanDiffFilterHolder.setAsyncSupported(true);
        context.addFilter(scanDiffFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var secretScanningFilterHolder = new FilterHolder(new SecretScanningFilter(commitConfig.getSecretScanning()));
        secretScanningFilterHolder.setAsyncSupported(true);
        context.addFilter(secretScanningFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

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

        var pushFinalizerHolder = new FilterHolder(new PushFinalizerFilter(serviceUrl, approvalGateway));
        pushFinalizerHolder.setAsyncSupported(true);
        context.addFilter(pushFinalizerHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var auditFilterHolder = new FilterHolder(new AuditLogFilter());
        auditFilterHolder.setAsyncSupported(true);
        context.addFilter(auditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
    }

    /**
     * No-op hook for dashboard deployments to register any additional proxy filters that are only relevant when a
     * dashboard is present. Currently unused — {@link AllowApprovedPushFilter} is registered inside
     * {@link #registerCoreFilters} at the correct chain position (filter ordering prevents post-hoc insertion). It is
     * harmless in standalone mode because no push records are ever set to {@code APPROVED} via the transparent-proxy
     * re-push flow when running without a dashboard. This method exists as an extension point for future dashboard-only
     * filters.
     */
    public static void registerApprovalFilters(
            ServletContextHandler context, GitProxyProvider provider, PushStore pushStore, String serviceUrl) {
        // Extension point — see Javadoc above.
    }
}
