package com.rbc.fogwall.jetty;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.rbc.fogwall.approval.ApprovalGateway;
import com.rbc.fogwall.config.BinaryBlobConfig;
import com.rbc.fogwall.config.CommitConfig;
import com.rbc.fogwall.config.ContentPatternConfig;
import com.rbc.fogwall.config.DiffScanConfig;
import com.rbc.fogwall.config.ScmOAuthConfig;
import com.rbc.fogwall.config.SecretScanConfig;
import com.rbc.fogwall.db.PushStore;
import com.rbc.fogwall.db.memory.InMemoryUrlRuleRegistry;
import com.rbc.fogwall.git.LocalRepositoryCache;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.GenericProxyProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.ee11.servlet.ServletMapping;
import org.junit.jupiter.api.Test;

/**
 * Verifies that server mode is served under both the canonical {@code /server} prefix and the legacy {@code /push}
 * alias (#538). The two prefixes are a routing concern — this asserts the wiring directly rather than through a live
 * push, which the e2e suite already covers on {@code /push}.
 */
class ServerPathAliasTest {

    private static FogwallProvider provider() {
        return GenericProxyProvider.builder()
                .name("gitea")
                .uri(java.net.URI.create("http://localhost:3000"))
                .build();
    }

    private static Set<String> registeredPathSpecs(ServletContextHandler context) {
        ServletMapping[] mappings = context.getServletHandler().getServletMappings();
        return Arrays.stream(mappings)
                .flatMap(m -> Arrays.stream(m.getPathSpecs()))
                .collect(Collectors.toSet());
    }

    @Test
    void registersServerModeUnderBothServerAndPushPrefixes() throws Exception {
        var provider = provider();
        var context = new ServletContextHandler("/", false, false);

        FogwallServletRegistrar.registerGitServlet(
                context,
                provider,
                new LocalRepositoryCache(),
                CommitConfig::defaultConfig,
                DiffScanConfig::defaultConfig,
                SecretScanConfig::defaultConfig,
                BinaryBlobConfig::defaultConfig,
                ScmOAuthConfig.defaultConfig(),
                ContentPatternConfig.defaultConfig(),
                mock(PushStore.class), // the factory requires its control dependencies even at registration time
                null, // serviceUrl
                mock(ApprovalGateway.class),
                null, // pushIdentityResolver
                null, // repoPermissionService
                10,
                30,
                false,
                0,
                0,
                0,
                new InMemoryUrlRuleRegistry(),
                null); // fetchStore

        String servletPath = provider.servletPath();
        Set<String> pathSpecs = registeredPathSpecs(context);

        assertTrue(
                pathSpecs.contains(FogwallServletRegistrar.SERVER_PATH_PREFIX + servletPath + "/*"),
                "server mode must be served under the canonical /server prefix; got " + pathSpecs);
        assertTrue(
                pathSpecs.contains(FogwallServletRegistrar.PUSH_PATH_PREFIX + servletPath + "/*"),
                "server mode must remain served under the legacy /push alias; got " + pathSpecs);
    }

    @Test
    void bothPrefixesGetDistinctlyNamedServletHolders() throws Exception {
        var provider = provider();
        var context = new ServletContextHandler("/", false, false);

        FogwallServletRegistrar.registerGitServlet(
                context,
                provider,
                new LocalRepositoryCache(),
                CommitConfig::defaultConfig,
                DiffScanConfig::defaultConfig,
                SecretScanConfig::defaultConfig,
                BinaryBlobConfig::defaultConfig,
                ScmOAuthConfig.defaultConfig(),
                ContentPatternConfig.defaultConfig(),
                mock(PushStore.class),
                null,
                mock(ApprovalGateway.class),
                null,
                null,
                10,
                30,
                false,
                0,
                0,
                0,
                new InMemoryUrlRuleRegistry(),
                null);

        List<String> gitHolderNames = Arrays.stream(context.getServletHandler().getServlets())
                .map(ServletHolder::getName)
                .filter(n -> n.startsWith("git-"))
                .sorted()
                .collect(Collectors.toList());

        assertEquals(
                List.of("git-gitea-push", "git-gitea-server"),
                gitHolderNames,
                "each server-mode prefix registers its own GitServlet holder so JGit init() runs once per instance");
    }
}
