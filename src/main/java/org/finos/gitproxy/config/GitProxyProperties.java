package org.finos.gitproxy.config;

import java.net.URI;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.finos.gitproxy.git.HttpAuthScheme;
import org.finos.gitproxy.provider.BitbucketProvider;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitLabProvider;
import org.finos.gitproxy.servlet.filter.AuthorizedByUrlFilter;
import org.finos.gitproxy.servlet.filter.FilterProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The main configuration properties for the GitProxy application. This class is used to configure the GitProxy
 * application, including the providers that are enabled, the filters that are applied to the proxy servlets, and the
 * base path for the proxy servlets.
 */
@ConfigurationProperties(value = "git-proxy", ignoreUnknownFields = false)
@Getter
@Setter
@ToString
public class GitProxyProperties {

    private Map<String, Provider> providers = new HashMap<>();
    private Filters filters = new Filters();

    /**
     * The base path for the proxy servlets. This is the path that will be used to register the servlets with the
     * servlet container. For each provider, the servlet will be registered at {@code basePath + provider.servletPath}.
     */
    private String basePath = "";

    // TODO: Find a way to force properties to be instantiated as a bean but early on in the lifecycle while still
    // delegating to Spring Boot to bind the properties. This is an open problem with the use of Environment + Binder
    // in the BeanFactoryPostProcessor where the properties bean is still not bound at the time the factories execute
    // _if_ no "git-proxy.*" properties are defined at all. Use of that factory for creating beans dynamically
    // implicitly makes it depend on config properties always being set. An ideal solution would be to simple create
    // this instance using this method below if @ConditionalOnMissingBean were true which is the standard idiom for
    // this sort of thing.
    // https://www.baeldung.com/spring-properties-beanfactorypostprocessor
    public static GitProxyProperties createDefault() {
        var enabledProvider = new Provider();
        enabledProvider.enabled = true;
        var providers = Map.of(
                GitHubProvider.NAME,
                enabledProvider,
                GitLabProvider.NAME,
                enabledProvider,
                BitbucketProvider.NAME,
                enabledProvider);
        var properties = new GitProxyProperties();
        properties.setProviders(providers);
        properties.setFilters(new Filters());
        return properties;
    }

    /**
     * Configuration properties for the GitProxy providers. Each provider option is designed to customize the actual
     * underlying proxy servlet that will be created for each provider. Most of the options exposed here match the list
     * of supported init parameters in {@link org.mitre.dsmiley.httpproxy.ProxyServlet}.
     */
    @Getter
    @Setter
    @ToString
    public static class Provider {
        private boolean enabled = false;
        private URI uri;
        private String servletPath;
        private boolean logProxy = true;
        private int connectTimeout = -1;
        private int readTimeout = -1;
    }

    /**
     * Properties for configurable filters that can be applied to the proxy servlets. Each filter has a set of common
     * properties that can be used to configure the behavior of the filter as well as specific properties that are
     * unique to the filter. Each filter is expected to be configured against a list of providers that it should be
     * applied to.
     */
    @Getter
    @Setter
    @ToString
    public static class Filters {
        private List<WhitelistFilterProperties> whitelists = new ArrayList<>();
        private GitHubUserAuthenticatedFilterProperties githubUserAuthenticated;
    }

    /**
     * Properties for the GitHub user authentication required filter. This filter is used to require that all requests
     * to GitHub are authenticated. This filter can be configured to require either a bearer token or a basic
     * authentication header.
     */
    @Getter
    @Setter
    @ToString
    public static class GitHubUserAuthenticatedFilterProperties extends FilterProperties {
        private Set<HttpAuthScheme> requiredAuthSchemes = Set.of(HttpAuthScheme.BEARER);
    }

    @Getter
    @Setter
    @ToString
    public static class WhitelistFilterProperties extends FilterProperties {

        private List<String> owners = new ArrayList<>();
        private List<String> names = new ArrayList<>();
        private List<String> slugs = new ArrayList<>();

        public List<String> getWhitelistForTarget(AuthorizedByUrlFilter.Target target) {
            return switch (target) {
                case OWNER -> owners;
                case NAME -> names;
                case SLUG -> slugs;
            };
        }
    }
}
