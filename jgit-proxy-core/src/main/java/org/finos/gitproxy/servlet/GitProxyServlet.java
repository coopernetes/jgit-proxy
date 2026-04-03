package org.finos.gitproxy.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee10.proxy.AsyncProxyServlet;
import org.finos.gitproxy.git.GitRequestDetails;

@Slf4j
public class GitProxyServlet extends AsyncProxyServlet.Transparent {
    public static final String GIT_REQUEST_ATTR = "gitproxy.gitRequest";
    public static final String ERROR_ATTR = "gitproxy.error";
    public static final String PRE_APPROVED_ATTR = "gitproxy.preApproved";
    public static final String SERVICE_URL_ATTR = "gitproxy.serviceUrl";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String GITHUB_REQUEST_ID_HEADER = "X-Github-Request-Id";

    @Override
    protected void service(HttpServletRequest clientRequest, HttpServletResponse proxyResponse)
            throws ServletException, IOException {
        var details = (GitRequestDetails) clientRequest.getAttribute(GIT_REQUEST_ATTR);
        var canProxy = details != null && details.getResult() == GitRequestDetails.GitResult.ALLOWED;
        if (canProxy) {
            super.service(clientRequest, proxyResponse);
        }
    }

    // TODO: Allow Via header to be sent if configured (enabled via opt-in)
    @Override
    protected void addViaHeader(Request proxyRequest) {
        // no-op - don't send "Via" header to upstream
    }

    @Override
    protected void addViaHeader(HttpServletRequest clientRequest, Request proxyRequest) {
        // no-op - don't send "Via" header to upstream
    }

    // TODO: Allow X-Forwarded-* headers to be sent if configured (enabled via opt-in)
    // TODO: Allow X-Forwarded-* headers to be customized
    @Override
    protected void addXForwardedHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
        // no-op - don't send "X-Forwarded-*" headers to upstream
    }

    @Override
    protected Request.Content proxyRequestContent(
            HttpServletRequest request, HttpServletResponse response, Request proxyRequest) throws IOException {
        if (request instanceof RequestBodyWrapper wrapper) {
            byte[] body = wrapper.getBody();
            if (body != null && body.length > 0) {
                log.debug("Sending {} bytes to upstream from wrapped request", body.length);
                return new BytesRequestContent(body);
            }
        }

        // Fall back to default implementation
        return super.proxyRequestContent(request, response, proxyRequest);
    }
}
