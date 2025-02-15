package com.github.coopernetes.jgitproxy.servlet.filter;

import com.github.coopernetes.jgitproxy.git.HttpOperation;
import com.github.coopernetes.jgitproxy.provider.AbstractGitProxyProvider;
import com.github.coopernetes.jgitproxy.provider.GitProxyProvider;
import com.github.coopernetes.jgitproxy.provider.ProviderConfiguration;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The main Filter class for the Git Proxy application. Every filter in the application must extend this class in order
 * for {@link ProviderConfiguration} to map each Filter to it's corresponding
 * {@link org.springframework.boot.web.servlet.ServletRegistrationBean} for proxying. The base class handles the
 * matching of the request URI to the hostname of the target URI of a provider. The application registers instances of
 * these Filters against corresponding servlets that proxy all known {@link AbstractGitProxyProvider} instances in the
 * application context.
 *
 * <p>This class enables the main use case below:
 *
 * <pre>
 *     Git HTTP client initiates request to {applicationUrl}/{providerHostname}/{providerPath...}
 *     -> Find Filter instances that match hostname of {@link AbstractGitProxyProvider#getUri()}
 *     -> Execute {@link #doHttpFilter(HttpServletRequest, HttpServletResponse, FilterChain)}
 * </pre>
 *
 * <p>The <a href="https://git-scm.com/docs/http-protocol">Git http protocol is stateless</a> and therefore each
 * implementing Filter only has to implement {@link #doHttpFilter} which is explicitly cast to
 * {@link HttpServletRequest} and {@link HttpServletResponse}.
 */
@Slf4j
public abstract class AbstractProviderAwareGitProxyFilter extends AbstractGitProxyFilter
        implements ProviderAwareGitProxyFilter {

    protected final GitProxyProvider provider;

    public AbstractProviderAwareGitProxyFilter(
            int order, Set<HttpOperation> appliedOperations, GitProxyProvider provider) {
        super(order, appliedOperations);
        this.provider = provider;
    }

    @Override
    public boolean shouldFilter(HttpServletRequest request) {
        return super.shouldFilter(request) && isMatchingProvider(request);
    }

    private boolean isMatchingProvider(HttpServletRequest request) {
        String servletPath = provider.servletPath();
        if (servletPath.endsWith(
                "/*")) { // TODO: Move into validation of config props so that it's not possible to have this
            // configuration
            servletPath = servletPath.substring(0, servletPath.length() - 2);
        }
        return request.getRequestURI().startsWith(servletPath);
    }

    /**
     * Returns the name of the bean that is created by this filter. This is used to identify the filter in the Spring
     * application context and provide unique names for each filter across all providers since it's not unexpected that
     * multiple filters are used in a single application.
     *
     * @return The name of the bean that is created by this filter.
     */
    @Override
    public String beanName() {
        return String.join("-", provider.getName(), "filter");
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" + "provider="
                + provider + ", order="
                + order + ", appliedOperations="
                + appliedOperations + '}';
    }
}
