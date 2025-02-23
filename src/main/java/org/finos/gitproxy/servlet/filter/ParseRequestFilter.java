package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyProviderServlet.GIT_REQUEST_ATTRIBUTE;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PacketLineIn;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.springframework.core.Ordered;

/**
 * Filter that extracts details about a git request and adds them to the request attributes. This filter is used to
 * extract the details so that they can be used by other filters for processing. This filter runs after the default
 * {@link ForceGitClientFilter}.
 */
@Slf4j
public class ParseRequestFilter extends AbstractProviderAwareGitProxyFilter implements RepositoryUrlFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    public ParseRequestFilter(GitProxyProvider provider) {
        super(ORDER, Set.of(HttpOperation.PUSH, HttpOperation.FETCH, HttpOperation.INFO), provider);
    }

    @Override
    public void doHttpFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var pushRequest = parse(request);
        request.setAttribute(GIT_REQUEST_ATTRIBUTE, pushRequest);
        chain.doFilter(request, response);
    }

    /**
     * Parse the {@link GitRequestDetails} details from the request body.
     *
     * @param request The HTTP request
     * @return The parsed push request
     */
    public GitRequestDetails parse(HttpServletRequest request) {
        // Parse the request body and return a push request
        var gr = new GitRequestDetails();
        gr.setProvider(provider);
        gr.getFilters().add(this);
        var op = determineOperation(request);
        gr.setOperation(op);
        gr.setOwner(getOwner(request.getPathInfo()));
        gr.setName(getName(request.getPathInfo()));
        gr.setSlug(getSlug(request.getPathInfo()));
        if (op == HttpOperation.INFO) {
            gr.setResult(GitRequestDetails.GitResult.ALLOWED);
        }
        if (op == HttpOperation.PUSH) {
            gr.setCommits(parseCommits(request));
            gr.setBranch(parseBranch(request));
        }
        return gr;
    }

    private List<Commit> parseCommits(HttpServletRequest request) {
        // Parse the request body and return a list of commits
        try {
            var pli = new PacketLineIn(request.getInputStream());
            log.debug(pli.readString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new ArrayList<>();
    }

    private String parseBranch(HttpServletRequest request) {
        // Parse the request body and return the branch
        return "";
    }
}
