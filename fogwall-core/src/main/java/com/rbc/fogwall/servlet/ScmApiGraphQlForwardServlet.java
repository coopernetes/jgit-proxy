package com.rbc.fogwall.servlet;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.net.FogwallHttpExecutor;
import com.rbc.fogwall.scmapi.ScmApiUserAgent;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;

/**
 * Thin forwarding servlet for GitHub's GraphQL SCM API dialect: relays an already-buffered, already-checked GraphQL
 * request to the provider's single GraphQL endpoint, using the caller's own {@code Authorization} header (BYO-token
 * model — fogwall never mints or substitutes its own credential), and relays the response back verbatim. The REST
 * dialects use {@link ScmApiRestForwardServlet}, whose target URL varies per request.
 */
@Slf4j
public class ScmApiGraphQlForwardServlet extends HttpServlet {

    private final String upstreamGraphqlUrl;

    public ScmApiGraphQlForwardServlet(String upstreamGraphqlUrl) {
        this.upstreamGraphqlUrl = upstreamGraphqlUrl;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        var context = (ScmApiRequestContext) request.getAttribute(ScmApiRequestContext.SCM_API_REQUEST_ATTR);
        String authHeader = ScmApiTokenExtractor.authHeaderName(request);
        // The gate filter's wrapper is what bounds the read. An unwrapped request means the chain that authorizes
        // this one is missing, so it fails rather than reading the stream raw.
        if (!(request instanceof RequestBodyWrapper wrapper)) {
            throw new IllegalStateException(
                    "SCM API request reached the forwarder unwrapped; the gate filter is not in the chain");
        }
        byte[] body = wrapper.getBody();

        Request upstreamRequest = Request.post(upstreamGraphqlUrl);
        if (authHeader != null) {
            upstreamRequest.addHeader(authHeader, request.getHeader(authHeader));
        }
        ScmApiUserAgent.relay(upstreamRequest, ScmApiUserAgent.of(request));

        try {
            // Streamed, with the upstream's content type relayed: nothing here assumes the response is JSON.
            upstreamRequest
                    .bodyByteArray(body, ContentType.APPLICATION_JSON)
                    .execute(FogwallHttpExecutor.instance())
                    .handleResponse(upstream -> {
                        response.setStatus(upstream.getCode());
                        var entity = upstream.getEntity();
                        if (entity != null && entity.getContentType() != null) {
                            response.setContentType(entity.getContentType());
                        }
                        if (entity != null && entity.getContentLength() >= 0) {
                            response.setContentLengthLong(entity.getContentLength());
                        }
                        if (entity != null) {
                            try (var in = entity.getContent()) {
                                in.transferTo(response.getOutputStream());
                            }
                        }
                        return null;
                    });

            if (context != null && context.getMutationField() != null) {
                context.setStatus(ScmApiActionStatus.FORWARDED);
            }
        } catch (IOException e) {
            log.warn("SCM API proxy forward failed: {}", e.getMessage());
            if (context != null && context.getMutationField() != null) {
                context.setStatus(ScmApiActionStatus.ERROR);
                context.setReason("Failed to forward to upstream: " + e.getMessage());
            }
            ScmApiErrorResponse.write(response, HttpServletResponse.SC_BAD_GATEWAY, "upstream forward failed");
        }
    }
}
