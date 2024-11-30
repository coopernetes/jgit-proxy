package com.github.coopernetes.jgitproxy.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.mitre.dsmiley.httpproxy.ProxyServlet;

@Slf4j
public class RewritingProxyServlet extends ProxyServlet {

    @Override
    protected HttpResponse doExecute(
            HttpServletRequest servletRequest, HttpServletResponse servletResponse, HttpRequest proxyRequest)
            throws IOException {
        var response = super.doExecute(servletRequest, servletResponse, proxyRequest);
        if (log.isDebugEnabled()) {
            if (response.containsHeader("X-Github-Request-Id")) {
                log.debug(
                        "X-Github-Request-Id: {}",
                        response.getFirstHeader("X-Github-Request-Id").getValue());
            }
        }
        return response;
    }

    @Override
    protected String rewriteUrlFromRequest(HttpServletRequest request) {
        log.debug("Rewriting URL from request: {}", request.getRequestURL());
        String rewrittenUrl = super.rewriteUrlFromRequest(request);
        for (var provider : GitHttpProviders.values()) {
            int firstMatchIndex = rewrittenUrl.indexOf("/" + provider.getHostname());
            if (firstMatchIndex != -1) {
                int secondMatchIndex = rewrittenUrl.indexOf("/" + provider.getHostname(), firstMatchIndex + 1);
                if (secondMatchIndex != -1) {
                    rewrittenUrl = rewrittenUrl.substring(0, secondMatchIndex)
                            + rewrittenUrl.substring(
                                    secondMatchIndex + provider.getHostname().length() + 1);
                }
            }
        }
        log.debug("Rewritten URL: {}", rewrittenUrl);
        return rewrittenUrl;
    }
}
