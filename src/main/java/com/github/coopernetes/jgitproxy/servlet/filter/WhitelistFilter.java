package com.github.coopernetes.jgitproxy.servlet.filter;

import com.github.coopernetes.jgitproxy.git.GitClient;
import com.github.coopernetes.jgitproxy.git.HttpOperation;
import com.github.coopernetes.jgitproxy.provider.AbstractGitProxyProvider;
import com.github.coopernetes.jgitproxy.provider.GitProxyProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * A generic whitelist filter that checks if the request is authorized to perform an {@link #appliedOperations} based on
 * the {@link #target} and a provided list of Strings that form the whitelist to compare against. Any request URL, which
 * is parsed according to the target, that does not match any of the whitelist values will result in an error being
 * returned to the client. The whitelist is typically created via configuration (Spring application properties). Custom
 * implementations can provide this list from any source.
 *
 * <p>This filter accepts any {@link AbstractGitProxyProvider}. Care must be taken to ensure that the configured
 * provider follows the common URL scheme when using the default behaviour of this class. Custom implementations can
 * always override those methods such as {@link #getSlug(String)}.
 */
@Slf4j
@ToString
public class WhitelistFilter extends AbstractProviderAwareGitProxyFilter implements AuthorizedByUrlFilter {

    private final List<String> whitelist;
    private final Target target;
    private static final Set<HttpOperation> DEFAULT_OPERATIONS = Set.of(HttpOperation.PUSH, HttpOperation.FETCH);

    public WhitelistFilter(int order, GitProxyProvider provider, List<String> whitelist, Target target) {
        super(order, DEFAULT_OPERATIONS, provider);
        this.whitelist = whitelist;
        this.target = target;
    }

    public WhitelistFilter(
            int order,
            Set<HttpOperation> appliedOperations,
            GitProxyProvider provider,
            List<String> whitelist,
            Target target) {
        super(order, appliedOperations, provider);
        this.whitelist = whitelist;
        this.target = target;
    }

    @Override
    public String beanName() {
        return String.join("-", provider.getName(), target.name().toLowerCase(), "whitelist", "filter");
    }

    @Override
    public boolean isAuthorized(Predicate<String> predicate) {
        return whitelist.stream().anyMatch(predicate);
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var matcher = createPredicate(target, request);
        if (!isAuthorized(matcher)) {
            var operation = determineOperation(request);
            String title =
                    GitClient.SymbolCodes.NO_ENTRY.emoji() + " Unauthorized! " + GitClient.SymbolCodes.NO_ENTRY.emoji();
            String action = operation == HttpOperation.PUSH ? "push to" : "fetch from";
            String message = "You are not authorized to " + action + " this repository.";
            sendGitError(
                    request,
                    response,
                    GitClient.formatForOperation(title, message, GitClient.AnsiColor.RED, operation));
        }
        chain.doFilter(request, response);
    }
}
