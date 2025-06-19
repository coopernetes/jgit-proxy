package org.finos.gitproxy.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.ee10.proxy.AsyncProxyServlet;
import org.eclipse.jetty.ee10.proxy.ProxyServlet;
import org.eclipse.jetty.util.Callback;
import org.finos.gitproxy.git.GitRequestDetails;

import java.io.IOException;

@Slf4j
public class GitProxyServlet extends ProxyServlet.Transparent {
    public static final String GIT_REQUEST_ATTRIBUTE = "org.finos.gitproxy.gitproxy.gitRequest";

    @Override
    protected void service(HttpServletRequest clientRequest, HttpServletResponse proxyResponse)
            throws ServletException, IOException {
        var details = (GitRequestDetails) clientRequest.getAttribute(GIT_REQUEST_ATTRIBUTE);
        var canProxy = details != null && details.getResult() == GitRequestDetails.GitResult.ALLOWED;
        if (canProxy) {
            super.service(clientRequest, proxyResponse);
        }
    }

    @Override
    protected Request.Content proxyRequestContent(HttpServletRequest request, HttpServletResponse response,
                                               Request proxyRequest) throws IOException {
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
