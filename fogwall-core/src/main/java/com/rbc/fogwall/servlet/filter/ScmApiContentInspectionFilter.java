package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.scmapi.ProposalContent;
import com.rbc.fogwall.scmapi.ProposalContentInspector;
import com.rbc.fogwall.scmapi.ProposalPayload;
import com.rbc.fogwall.servlet.RequestBodyWrapper;
import com.rbc.fogwall.servlet.ScmApiErrorResponse;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inspects the prose a proposal carries before it is forwarded, applying the same blocked-content and secret-scanning
 * rules that already guard a push.
 *
 * <p>Without this a contributor blocked from pushing a secret could paste it into a pull request description and
 * fogwall would relay it verbatim — the request is a permitted operation on a repository they hold {@code PROPOSE} on,
 * so every earlier stage in the chain says yes.
 *
 * <p>Runs after the dialect's gate filter, so it only ever inspects a request that was going to be forwarded, and the
 * audit record already names the target repository. A violation is recorded as {@link ScmApiActionStatus#REJECTED}: the
 * caller was entitled to the operation, but not to publish that content through it.
 */
@Slf4j
@RequiredArgsConstructor
public class ScmApiContentInspectionFilter implements Filter {

    private static final JsonMapper MAPPER = new JsonMapper();

    private final ProposalContentInspector inspector;
    /** Pulls the dialect's prose fields out of the parsed request body, for attribution. */
    private final Function<JsonNode, List<ProposalContent>> extractor;
    /**
     * Values a dialect can only surface by parsing beyond the JSON — GraphQL's own string literals. Empty for the REST
     * dialects, whose bodies are JSON all the way down.
     */
    private final Function<JsonNode, List<String>> additionalReadings;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        var context = (ScmApiRequestContext) httpRequest.getAttribute(SCM_API_REQUEST_ATTR);

        // A read never reaches a mutation field, and there is nothing being published to inspect.
        if (context == null || context.getMutationField() == null) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body = readBody(request);
        JsonNode parsed = parse(body);
        List<String> violations = inspector.inspect(
                parsed == null ? List.of() : extractor.apply(parsed),
                ProposalPayload.of(
                        body,
                        parsed,
                        httpRequest.getQueryString(),
                        parsed == null ? List.of() : additionalReadings.apply(parsed)));
        if (violations.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String reason = "Content rejected: " + String.join("; ", violations);
        log.debug("SCM API proxy content inspection rejected the request: {}", reason);
        context.setStatus(ScmApiActionStatus.REJECTED);
        context.setReason(reason);
        ScmApiErrorResponse.write(((HttpServletResponse) response), HttpServletResponse.SC_FORBIDDEN, reason);
    }

    private static byte[] readBody(ServletRequest request) throws IOException {
        return request instanceof RequestBodyWrapper wrapper
                ? wrapper.getBody()
                : request.getInputStream().readAllBytes();
    }

    /**
     * {@code null} when the body will not parse. The raw bytes are still scanned in that case — an unparseable body is
     * not a reason to forward something uninspected.
     */
    private static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("Could not parse proposal body for field attribution: {}", e.getMessage());
            return null;
        }
    }
}
