package com.rbc.fogwall.servlet;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.net.FogwallHttpExecutor;
import com.rbc.fogwall.scmapi.ScmApiUserAgent;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;

/**
 * Thin forwarding servlet for the REST-based SCM API proxy dialects, GitLab and Forgejo. Unlike
 * {@link ScmApiGraphQlForwardServlet} (GitHub's single fixed GraphQL endpoint), a REST dialect's target URL varies per
 * request: this relays the same sub-path and query string the caller hit, under the provider's REST API base URL,
 * preserving method and body. Uses the caller's own {@code Authorization} header unchanged — same BYO-token model as
 * the GraphQL forwarder.
 */
@Slf4j
public class ScmApiRestForwardServlet extends HttpServlet {

    private final URI upstreamApiBaseUri;
    private final ScmApiRestPathPolicy.EncodedSeparators encodedSeparators;

    public ScmApiRestForwardServlet(
            String upstreamApiBaseUrl, ScmApiRestPathPolicy.EncodedSeparators encodedSeparators) {
        this.upstreamApiBaseUri = URI.create(upstreamApiBaseUrl);
        this.encodedSeparators = encodedSeparators;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        URI target = upstreamUrl(request);
        if (target == null) {
            rejectTarget(response);
            return;
        }
        forward(request, response, Request.get(target));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        URI target = upstreamUrl(request);
        if (target == null) {
            rejectTarget(response);
            return;
        }
        forward(request, response, Request.post(target).bodyByteArray(readBody(request), ContentType.APPLICATION_JSON));
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        URI target = upstreamUrl(request);
        if (target == null) {
            rejectTarget(response);
            return;
        }
        forward(request, response, Request.put(target).bodyByteArray(readBody(request), ContentType.APPLICATION_JSON));
    }

    /**
     * DELETE carries a body here, unlike most uses of the method: {@code tea issue edit --remove-assignees} sends the
     * logins to drop in the body of {@code DELETE /issues/{n}/assignees}, so forwarding the method without the entity
     * would reach the upstream as a request to remove nobody.
     */
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        URI target = upstreamUrl(request);
        if (target == null) {
            rejectTarget(response);
            return;
        }
        forward(
                request,
                response,
                Request.delete(target).bodyByteArray(readBody(request), ContentType.APPLICATION_JSON));
    }

    /**
     * {@code HttpServlet} has no {@code doPatch} and answers 405 for the method, so PATCH is dispatched here instead.
     * Forgejo updates and closes are PATCH — {@code tea pr close} sends {@code PATCH /pulls/{n}} — so without this the
     * allowlist admits those operations and the forwarder then refuses them.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        if (!"PATCH".equalsIgnoreCase(request.getMethod())) {
            super.service(request, response);
            return;
        }
        URI target = upstreamUrl(request);
        if (target == null) {
            rejectTarget(response);
            return;
        }
        forward(
                request,
                response,
                Request.patch(target).bodyByteArray(readBody(request), ContentType.APPLICATION_JSON));
    }

    /**
     * The upstream URI for this request, or {@code null} when the caller's path is not one this servlet will forward.
     *
     * <p>The sub-path is relayed <b>as the caller sent it</b>, still encoded — taking it from
     * {@link HttpServletRequest#getPathInfo()} would decode {@code /projects/acme%2Fwidgets} to
     * {@code /projects/acme/widgets}, which GitLab reads as a different (and usually nonexistent) project, the same
     * decode hazard {@link ScmApiRestPath} exists to avoid on the authorization side.
     *
     * <p>Because that path is caller-controlled and is concatenated onto the provider's base, the result is checked to
     * still address the configured provider before it is used: same scheme, host, port, and still under the base path.
     * The gate filters apply {@link ScmApiRestPathPolicy} before this point; repeating it here keeps the guarantee
     * attached to the request that is actually sent, rather than resting on filter ordering.
     */
    private URI upstreamUrl(HttpServletRequest request) {
        String subPath = ScmApiRestPath.rawSubPath(request);
        if (!ScmApiRestPathPolicy.isForwardable(subPath, encodedSeparators)) {
            return null;
        }
        String query = request.getQueryString();
        // Anything but GET is a write in every dialect fogwall proxies, and no write carries a query parameter.
        boolean mutation = !"GET".equalsIgnoreCase(request.getMethod());
        if (ScmApiQueryPolicy.refusedParameter(query, mutation) != null) {
            return null;
        }
        URI target;
        try {
            target = new URI(upstreamApiBaseUri + subPath + (query != null ? "?" + query : ""));
        } catch (URISyntaxException e) {
            return null;
        }
        return addressesConfiguredUpstream(target) ? target : null;
    }

    /** Whether {@code target} still points at the provider this servlet was configured for. */
    private boolean addressesConfiguredUpstream(URI target) {
        return target.isAbsolute()
                && !target.isOpaque()
                && Objects.equals(target.getScheme(), upstreamApiBaseUri.getScheme())
                && Objects.equals(target.getHost(), upstreamApiBaseUri.getHost())
                && target.getPort() == upstreamApiBaseUri.getPort()
                && target.getRawUserInfo() == null
                && target.getRawPath() != null
                && target.getRawPath().startsWith(upstreamApiBaseUri.getRawPath());
    }

    private static void rejectTarget(HttpServletResponse response) throws IOException {
        ScmApiErrorResponse.write(
                response,
                HttpServletResponse.SC_BAD_REQUEST,
                "Malformed request path, or a query parameter this proxy does not accept");
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        // The gate filter's wrapper is what bounds the read. An unwrapped request means the chain that authorizes
        // this one is missing, so it fails rather than reading the stream raw.
        if (!(request instanceof RequestBodyWrapper wrapper)) {
            throw new IllegalStateException(
                    "SCM API request reached the forwarder unwrapped; the gate filter is not in the chain");
        }
        return wrapper.getBody();
    }

    private void forward(HttpServletRequest request, HttpServletResponse response, Request upstreamRequest)
            throws IOException {
        var context = (ScmApiRequestContext) request.getAttribute(ScmApiRequestContext.SCM_API_REQUEST_ATTR);
        String authHeader = ScmApiTokenExtractor.authHeaderName(request);
        if (authHeader != null) {
            upstreamRequest.addHeader(authHeader, request.getHeader(authHeader));
        }
        ScmApiUserAgent.relay(upstreamRequest, ScmApiUserAgent.of(request));

        try {
            // Streamed rather than buffered: nothing inspects a response, and a blob read can be large. The
            // encoded-separator policy deliberately admits Forgejo's raw/contents/media endpoints — fj fetches a pull
            // request template from one before every create — so a response here is not always JSON either. The
            // upstream's own content type is relayed instead of a guess.
            upstreamRequest.execute(FogwallHttpExecutor.instance()).handleResponse(upstream -> {
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
