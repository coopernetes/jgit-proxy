package org.finos.gitproxy.jetty;

import org.finos.gitproxy.approval.ApprovalGateway;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.FetchStore;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.git.LocalRepositoryCache;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.service.PushIdentityResolver;
import org.finos.gitproxy.tls.SslUtil;
import org.finos.gitproxy.user.UserStore;

/**
 * Immutable snapshot of all shared runtime dependencies wired up at server startup. Passed to
 * {@link GitProxyServletRegistrar#registerProviders} to avoid the combinatorial parameter explosion that would
 * otherwise appear on each registration method.
 *
 * <p>Built by {@link org.finos.gitproxy.jetty.config.JettyConfigurationBuilder#buildProxyContext()} or its overload
 * that accepts an explicit {@link ApprovalGateway} (used by the dashboard application which always forces
 * {@link org.finos.gitproxy.approval.UiApprovalGateway}).
 */
public record GitProxyContext(
        PushStore pushStore,
        FetchStore fetchStore,
        UserStore userStore,
        RepoPermissionService repoPermissionService,
        PushIdentityResolver pushIdentityResolver,
        ApprovalGateway approvalGateway,
        CommitConfig commitConfig,
        String serviceUrl,
        int heartbeatIntervalSeconds,
        boolean failFast,
        int upstreamConnectTimeoutSeconds,
        int proxyConnectTimeoutSeconds,
        LocalRepositoryCache storeForwardCache,
        LocalRepositoryCache proxyCache,
        SslUtil.UpstreamTls upstreamTls) {}
