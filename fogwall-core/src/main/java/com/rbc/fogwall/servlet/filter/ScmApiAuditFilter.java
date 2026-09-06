package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;

import com.rbc.fogwall.db.ScmApiActionStore;
import com.rbc.fogwall.db.model.ScmApiActionRecord;
import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes one {@link ScmApiActionRecord} per proxied SCM API mutation — never per read, matching the "same bar as the
 * push path" auditability requirement.
 *
 * <p>Wraps the whole chain in try-finally so it runs whichever way the request resolved — a gate filter's denial, or a
 * forward outcome recorded once the upstream response is known. Must be registered FIRST so its {@code finally} block
 * executes last.
 *
 * <p>A pure read leaves {@link ScmApiRequestContext}'s {@code mutationField} null and is never audited individually.
 */
@Slf4j
@RequiredArgsConstructor
public class ScmApiAuditFilter implements Filter {

    /**
     * Column widths in {@code scm_api_action_records}. Every value bounded here is caller-controlled: a request header,
     * a GraphQL field name, a node ID from the caller's own variables, an owner/repo decoded out of the request path.
     * Exceeding a column throws, and the write is wrapped in a catch, so an over-long value would silently erase the
     * record — including for a mutation that already reached the upstream.
     */
    private static final int MAX_USER_AGENT = 512;

    private static final int MAX_MUTATION_FIELD = 100;

    private static final int MAX_NODE_ID = 255;

    private static final int MAX_REPO_SEGMENT = 255;

    private static final int MAX_CLIENT_VERSION = 64;

    /**
     * {@code reason} is TEXT, so this bounds the row rather than the column. A refusal names what was refused, and that
     * is caller-controlled — a request path, a GraphQL field name, a parser message quoting the offending token.
     */
    private static final int MAX_REASON = 2000;

    private final ScmApiActionStore scmApiActionStore;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            var context = (ScmApiRequestContext) ((HttpServletRequest) request).getAttribute(SCM_API_REQUEST_ATTR);
            if (context != null && shouldRecord(context)) {
                try {
                    scmApiActionStore.save(ScmApiActionRecord.builder()
                            .status(statusOf(context))
                            .provider(context.getProvider())
                            .scmUsername(context.getScmLogin())
                            .resolvedUser(context.getResolvedUser())
                            .repoOwner(abbreviate(context.getRepoOwner(), MAX_REPO_SEGMENT))
                            .repoName(abbreviate(context.getRepoName(), MAX_REPO_SEGMENT))
                            .mutationField(abbreviate(context.getMutationField(), MAX_MUTATION_FIELD))
                            .nodeId(abbreviate(context.getNodeId(), MAX_NODE_ID))
                            .nodeType(context.getNodeType())
                            .reason(abbreviate(reasonOf(context), MAX_REASON))
                            .variablesJson(variablesJsonOf(context))
                            .userAgent(abbreviate(context.getUserAgent(), MAX_USER_AGENT))
                            .clientType(
                                    context.getClientType() == null
                                            ? null
                                            : context.getClientType().name())
                            .clientVersion(abbreviate(context.getClientVersion(), MAX_CLIENT_VERSION))
                            .build());
                } catch (RuntimeException e) {
                    log.error("Failed to write SCM API action audit record", e);
                }
            }
        }
    }

    /**
     * Caps a caller-influenced value, marking it so a reader can tell a truncated value from a short one. Every value
     * meets its column here, so a new refusal message cannot reintroduce the problem by forgetting to bound itself.
     */
    private static String abbreviate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
    }

    /**
     * What earns a row: a mutation whatever its outcome, or a refusal of an authenticated caller.
     *
     * <p>A refusal is recorded even with no operation named, since an endpoint matching no allowlist rule is refused
     * before there is one to name — that is how an operator sees a CLI upgrade calling something the allowlist does not
     * know.
     *
     * <p>Authentication is required for that second case: writing a row costs valid credentials, so an anonymous caller
     * cannot fill the trail. Successful reads stay unrecorded, keeping the read path near pass-through.
     */
    private static boolean shouldRecord(ScmApiRequestContext context) {
        if (context.getMutationField() != null) {
            return true;
        }
        return context.getResolvedUser() != null
                && context.getStatus() != null
                && context.getStatus() != ScmApiActionStatus.FORWARDED;
    }

    /**
     * A mutation reaching this filter always has a status; it is unset only when something threw before any stage
     * recorded an outcome. Recorded as {@link ScmApiActionStatus#ERROR} rather than left null, which would fail the
     * write and lose the record.
     */
    private static ScmApiActionStatus statusOf(ScmApiRequestContext context) {
        return context.getStatus() == null ? ScmApiActionStatus.ERROR : context.getStatus();
    }

    /**
     * The request variables are the audit evidence, except when content inspection refused the request: storing them
     * would put the secret fogwall just blocked into its own database. The reason still names what matched, redacted.
     */
    private static String variablesJsonOf(ScmApiRequestContext context) {
        return context.getStatus() == ScmApiActionStatus.REJECTED ? null : context.getVariablesJson();
    }

    private static String reasonOf(ScmApiRequestContext context) {
        if (context.getReason() != null) {
            return context.getReason();
        }
        return context.getStatus() == null ? "Request failed before an outcome was recorded" : null;
    }
}
