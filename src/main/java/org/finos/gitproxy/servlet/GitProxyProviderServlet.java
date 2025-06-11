package org.finos.gitproxy.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.mitre.dsmiley.httpproxy.ProxyServlet;

@Slf4j
@RequiredArgsConstructor
public class GitProxyProviderServlet extends ProxyServlet {

    private final GitProxyProvider provider;

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String GITHUB_REQUEST_ID_HEADER = "X-Github-Request-Id";
    public static final String ERROR_ATTRIBUTE = "org.finos.gitproxy.gitproxy.error";
    public static final String GIT_REQUEST_ATTRIBUTE = "org.finos.gitproxy.gitproxy.gitRequest";

    /**
     * Executes the proxying request if a specific set of common conditions are met. These include:
     *
     * <ul>
     *   <li>the client response has already been committed (errors, blocked)
     *   <li>the request is authorized to proceed (e.g. a push is allowed)
     * </ul>
     */
    @Override
    protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
            throws ServletException, IOException {
        var details = (GitRequestDetails) servletRequest.getAttribute(GIT_REQUEST_ATTRIBUTE);
        var canProxy = details != null && details.getResult() == GitRequestDetails.GitResult.ALLOWED;
        if (!servletResponse.isCommitted() && canProxy) {
            super.service(servletRequest, servletResponse);
        }
    }

    /**
     * Delegates the actual proxying implementation to the parent {@link ProxyServlet} class and adds debugging logs if
     * log level is set to DEBUG. The {@link #REQUEST_ID_HEADER} and {@link #GITHUB_REQUEST_ID_HEADER} headers are
     * logged if present in the response for troubleshooting purposes.
     *
     * @param servletRequest the incoming request
     * @param servletResponse the proxied response
     * @param proxyRequest
     * @return
     * @throws IOException
     */
    @Override
    protected HttpResponse doExecute(
            HttpServletRequest servletRequest, HttpServletResponse servletResponse, HttpRequest proxyRequest)
            throws IOException {
        var response = super.doExecute(servletRequest, servletResponse, proxyRequest);
        if (log.isDebugEnabled()) {
            // https://docs.gitlab.com/ee/administration/logs/tracing_correlation_id.html
            if (response.containsHeader(REQUEST_ID_HEADER)) {
                log.debug(
                        "{}: {}",
                        REQUEST_ID_HEADER,
                        response.getFirstHeader(REQUEST_ID_HEADER).getValue());
            }
            // https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api?apiVersion=2022-11-28#about-the-response-code-and-headers
            if (response.containsHeader(GITHUB_REQUEST_ID_HEADER)) {
                log.debug(
                        "{}: {}",
                        GITHUB_REQUEST_ID_HEADER,
                        response.getFirstHeader(GITHUB_REQUEST_ID_HEADER).getValue());
            }
        }
        return response;
    }

    @Override
    protected String rewriteUrlFromRequest(HttpServletRequest request) {
        String rewrittenUrl = super.rewriteUrlFromRequest(request);
        var providerHostname = provider.getUri().getHost();
        int firstMatchIndex = rewrittenUrl.indexOf("/" + providerHostname);
        if (firstMatchIndex != -1) {
            int secondMatchIndex = rewrittenUrl.indexOf("/" + providerHostname, firstMatchIndex + 1);
            if (secondMatchIndex != -1) {
                rewrittenUrl = rewrittenUrl.substring(0, secondMatchIndex)
                        + rewrittenUrl.substring(secondMatchIndex + providerHostname.length() + 1);
            }
        }
        return rewrittenUrl;
    }
}
