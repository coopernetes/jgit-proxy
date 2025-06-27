package org.finos.gitproxy;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.finos.gitproxy.git.HttpAuthScheme;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.servlet.GitProxyServlet;
import org.finos.gitproxy.servlet.filter.*;

public class GitProxyApplication {
    public static void main(String[] args) throws Exception {
        var threadPool = new QueuedThreadPool();
        threadPool.setName("server");

        var server = new Server(threadPool);

        var connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        var gitHubProvider = new GitHubProvider("");
        String urlPattern = gitHubProvider.servletMapping();

        var context = new ServletContextHandler("/", false, false);

        var forceGitClientFilter = new ForceGitClientFilter();
        var forceGitClientFilterHolder = new FilterHolder(forceGitClientFilter);
        forceGitClientFilterHolder.setAsyncSupported(true);
        context.addFilter(forceGitClientFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var parseRequestFilter = new ParseGitRequestFilter(gitHubProvider);
        var parseRequestFilterHolder = new FilterHolder(parseRequestFilter);
        parseRequestFilterHolder.setAsyncSupported(true);
        context.addFilter(parseRequestFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var githubAuthorizedFilter = new GitHubUserAuthenticatedFilter(
                10, gitHubProvider, Set.of(HttpAuthScheme.BASIC, HttpAuthScheme.TOKEN, HttpAuthScheme.BEARER));
        var ghFilterHolder = new FilterHolder(githubAuthorizedFilter);
        context.addFilter(ghFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var whitelistFilters = List.of(
                //                                new WhitelistByUrlFilter(100, gitHubProvider, List.of("coopernetes"),
                //                 RepositoryUrlFilter.Target.OWNER),
                //                                new WhitelistByUrlFilter(
                //                                        101, gitHubProvider, List.of("jgit-proxy", "test-repo"),
                //                 RepositoryUrlFilter.Target.NAME),
                new WhitelistByUrlFilter(
                        102,
                        gitHubProvider,
                        List.of("finos/git-proxy", "coopernetes/test-repo"),
                        RepositoryUrlFilter.Target.SLUG));
        var whitelistAggregateFilter = new WhitelistAggregateFilter(20, gitHubProvider, whitelistFilters);
        var whitelistAggFilterHolder = new FilterHolder(whitelistAggregateFilter);
        whitelistAggFilterHolder.setAsyncSupported(true);
        context.addFilter(whitelistAggFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var auditFilter = new AuditLogFilter();
        var auditFilterHolder = new FilterHolder(auditFilter);
        auditFilterHolder.setAsyncSupported(true);
        context.addFilter(auditFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));

        var proxyServlet = new GitProxyServlet();
        var proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameter("proxyTo", "https://github.com");
        proxyServletHolder.setInitParameter("prefix", "/github.com");
        proxyServletHolder.setInitParameter("hostHeader", "github.com");
        proxyServletHolder.setInitParameter("preserveHost", "false");
        context.addServlet(proxyServletHolder, urlPattern);

        server.setHandler(context);

        server.start();
        System.out.println("Server started at http://localhost:8080/");
    }
}
