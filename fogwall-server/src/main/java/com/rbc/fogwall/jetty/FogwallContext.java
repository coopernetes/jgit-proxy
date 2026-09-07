package com.rbc.fogwall.jetty;

import com.rbc.fogwall.approval.ApprovalGateway;
import com.rbc.fogwall.approval.UiApprovalGateway;
import com.rbc.fogwall.config.CommitConfig;
import com.rbc.fogwall.config.JettyConfigurationBuilder;
import com.rbc.fogwall.db.FetchStore;
import com.rbc.fogwall.db.PushStore;
import com.rbc.fogwall.db.ScmApiActionStore;
import com.rbc.fogwall.db.UrlRuleRegistry;
import com.rbc.fogwall.git.LocalRepositoryCache;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.ProviderRegistry;
import com.rbc.fogwall.scmapi.GitHubNodeIdCache;
import com.rbc.fogwall.scmapi.GitLabProjectIdCache;
import com.rbc.fogwall.service.PushIdentityResolver;
import com.rbc.fogwall.service.SshScmIdentityEnricher;
import com.rbc.fogwall.tls.SslUtil;
import com.rbc.fogwall.user.UserStore;

/**
 * Immutable snapshot of all shared runtime dependencies wired up at server startup. Passed to
 * {@link FogwallServletRegistrar#registerProviders} to avoid the combinatorial parameter explosion that would otherwise
 * appear on each registration method.
 *
 * <p>Built by {@link JettyConfigurationBuilder#buildProxyContext()} or its overload that accepts an explicit
 * {@link ApprovalGateway} (used by the dashboard application which always forces {@link UiApprovalGateway}).
 */
public record FogwallContext(
        PushStore pushStore,
        FetchStore fetchStore,
        UserStore userStore,
        UrlRuleRegistry urlRuleRegistry,
        RepoPermissionService repoPermissionService,
        PushIdentityResolver pushIdentityResolver,
        ApprovalGateway approvalGateway,
        CommitConfig commitConfig,
        String serviceUrl,
        int heartbeatIntervalSeconds,
        int approvalTimeoutSeconds,
        boolean failFast,
        long maxPushBytes,
        long maxObjectSizeBytes,
        int upstreamConnectTimeoutSeconds,
        int proxyConnectTimeoutSeconds,
        LocalRepositoryCache serverCache,
        LocalRepositoryCache proxyCache,
        SslUtil.UpstreamTls upstreamTls,
        ProviderRegistry providerRegistry,
        SshScmIdentityEnricher sshScmIdentityEnricher,
        GitHubNodeIdCache gitHubNodeIdCache,
        GitLabProjectIdCache gitLabProjectIdCache,
        ScmApiActionStore scmApiActionStore) {}
