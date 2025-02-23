package org.finos.gitproxy.servlet.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.AbstractGitProxyProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.provider.ProviderConfiguration;

/**
 * A {@link GitProxyFilter} that is aware of the {@link GitProxyProvider} it is applied to and . Every filter in the
 * application must extend this class in order for {@link ProviderConfiguration} to map each Filter to it's
 * corresponding {@link org.springframework.boot.web.servlet.ServletRegistrationBean} for proxying. The base class
 * handles the matching of the request URI to the hostname of the target URI of a provider. The application registers
 * instances of these Filters against corresponding servlets that proxy all known {@link AbstractGitProxyProvider}
 * instances in the application context.
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
    public Predicate<HttpServletRequest> shouldFilter() {
        return super.shouldFilter()
                .and((HttpServletRequest request) -> request.getRequestURI().startsWith(provider.servletPath()));
    }

    /**
     * Returns the name of the bean that is created by this filter. This is used to identify the filter in the Spring
     * application context and provide unique names for each filter across all providers since it's not unexpected that
     * multiple filters would be used in a single application matching different providers.
     *
     * @return The name of the bean that is created by this filter.
     */
    @Override
    public String beanName() {
        return String.join("-", provider.getName(), this.getClass().getSimpleName());
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" + "provider="
                + provider.getName() + ", order="
                + order + ", appliedOperations="
                + applicableOperations + '}';
    }
}
