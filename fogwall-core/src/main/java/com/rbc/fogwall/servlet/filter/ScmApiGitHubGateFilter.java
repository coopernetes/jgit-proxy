package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.GitHubProvider;
import com.rbc.fogwall.scmapi.GitHubMutationAllowlist;
import com.rbc.fogwall.scmapi.GitHubNodeIdResolver;
import com.rbc.fogwall.scmapi.GraphQlMutationParser;
import com.rbc.fogwall.scmapi.GraphQlParseException;
import com.rbc.fogwall.scmapi.MutationNodeIdExtractor;
import com.rbc.fogwall.scmapi.MutationNodeIdRef;
import com.rbc.fogwall.scmapi.OwnerRepo;
import com.rbc.fogwall.scmapi.ScmApiGraphQlRequest;
import com.rbc.fogwall.scmapi.ScmApiGraphQlRequestParser;
import com.rbc.fogwall.servlet.PushTooLargeException;
import com.rbc.fogwall.servlet.RequestBodyWrapper;
import com.rbc.fogwall.servlet.ScmApiErrorResponse;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import com.rbc.fogwall.servlet.ScmApiTokenExtractor;
import graphql.language.Field;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The SCM API proxy decision pipeline for GitHub's GraphQL dialect: parses the request, allowlists the mutation on the
 * parsed AST, resolves its opaque node ID to {@code owner/repo}, and authorizes through the permission engine. Reads
 * (pure {@code query} documents) are gated by authentication alone — no allowlist, no resolution, no extra round-trip.
 * See {@link ScmApiGitLabGateFilter} for the REST dialects.
 *
 * <p>Denies are terminal: this filter responds directly without calling the chain further, so the forward servlet never
 * sees a denied request.
 */
@Slf4j
@RequiredArgsConstructor
public class ScmApiGitHubGateFilter implements Filter {

    private final GitHubProvider provider;
    private final GitHubNodeIdResolver gitHubNodeIdResolver;
    private final RepoPermissionService repoPermissionService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        var context = (ScmApiRequestContext) httpRequest.getAttribute(SCM_API_REQUEST_ATTR);

        // Cheap pre-check, then a counting read: a chunked request declares no length, so the wrapper is the bound.
        long declared = httpRequest.getContentLengthLong();
        if (declared > ScmApiRequestContext.MAX_BODY_BYTES) {
            tooLarge(context, httpResponse, declared);
            return;
        }
        RequestBodyWrapper wrapper;
        try {
            wrapper = new RequestBodyWrapper(httpRequest, ScmApiRequestContext.MAX_BODY_BYTES);
        } catch (PushTooLargeException e) {
            tooLarge(context, httpResponse, e.getBytesRead());
            return;
        }

        ScmApiGraphQlRequest graphQlRequest;
        Optional<Field> mutation;
        try {
            graphQlRequest = ScmApiGraphQlRequestParser.parse(wrapper.getBody());
            mutation =
                    GraphQlMutationParser.selectMutationField(graphQlRequest.query(), graphQlRequest.operationName());
        } catch (GraphQlParseException e) {
            fail(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "Malformed GraphQL request: " + e.getMessage());
            return;
        }

        if (mutation.isEmpty()) {
            handleRead(httpResponse, chain, wrapper);
            return;
        }

        Field mutationAst = mutation.get();
        String mutationField = mutationAst.getName();
        context.setMutationField(mutationField);
        context.setVariablesJson(
                graphQlRequest.variables() != null ? graphQlRequest.variables().toString() : null);

        if (!GitHubMutationAllowlist.isAllowed(mutationField)) {
            deny(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Mutation '" + mutationField + "' is not allowlisted");
            return;
        }

        Optional<MutationNodeIdRef> nodeIdRef =
                MutationNodeIdExtractor.extract(mutationAst, graphQlRequest.variables());
        if (nodeIdRef.isEmpty()) {
            fail(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "Could not extract a target node ID from mutation '" + mutationField + "'");
            return;
        }
        context.setNodeId(nodeIdRef.get().nodeId());
        context.setNodeType(nodeIdRef.get().nodeType().name());

        String callerToken = ScmApiTokenExtractor.extractToken(httpRequest);
        Optional<OwnerRepo> ownerRepo = gitHubNodeIdResolver.resolve(provider, nodeIdRef.get(), callerToken);
        if (ownerRepo.isEmpty()) {
            fail(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Could not resolve node ID '" + nodeIdRef.get().nodeId() + "' to a repository");
            return;
        }
        context.setRepoOwner(ownerRepo.get().owner());
        context.setRepoName(ownerRepo.get().name());

        String path = "/" + ownerRepo.get().owner() + "/" + ownerRepo.get().name();
        if (!repoPermissionService.isAllowedToPropose(context.getResolvedUser(), provider.getProviderId(), path)) {
            denyWithoutNamingTarget(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_FORBIDDEN,
                    "User '" + context.getResolvedUser() + "' is not permitted to perform API mutations on " + path);
            return;
        }

        chain.doFilter(wrapper, response);
    }

    private void handleRead(HttpServletResponse response, FilterChain chain, RequestBodyWrapper wrapper)
            throws IOException, ServletException {
        chain.doFilter(wrapper, response);
    }

    /**
     * Refuses the request because a policy said no — not allowlisted, not enabled, or the caller lacks the grant.
     * Recorded as {@link ScmApiActionStatus#DENIED}: fogwall reached a decision, and the decision was no.
     */
    private static void deny(ScmApiRequestContext context, HttpServletResponse response, int status, String reason)
            throws IOException {
        respond(context, response, status, ScmApiActionStatus.DENIED, reason);
    }

    /** A denial whose reason names a repository, told to the caller without it. */
    private static void denyWithoutNamingTarget(
            ScmApiRequestContext context, HttpServletResponse response, int status, String reason) throws IOException {
        respond(
                context,
                response,
                status,
                ScmApiActionStatus.DENIED,
                reason,
                "Not permitted to perform this operation on its target");
    }

    /**
     * Refuses an over-size body. Recorded as {@link ScmApiActionStatus#ERROR}: fogwall reached no decision, having
     * declined to read enough of the request to have one.
     */
    private static void tooLarge(ScmApiRequestContext context, HttpServletResponse response, long actualBytes)
            throws IOException {
        respond(
                context,
                response,
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                ScmApiActionStatus.ERROR,
                "Request body exceeds " + ScmApiRequestContext.MAX_BODY_BYTES + " bytes (" + actualBytes + ")");
    }

    /**
     * Refuses the request because no decision was reachable — unparseable input, or a target that resolved to no
     * repository. Recorded as {@link ScmApiActionStatus#ERROR} rather than DENIED so filtering the trail for denials
     * shows policy violations rather than malformed requests. Fails closed either way.
     */
    private static void fail(ScmApiRequestContext context, HttpServletResponse response, int status, String reason)
            throws IOException {
        respond(context, response, status, ScmApiActionStatus.ERROR, reason);
    }

    private static void respond(
            ScmApiRequestContext context,
            HttpServletResponse response,
            int status,
            ScmApiActionStatus actionStatus,
            String reason)
            throws IOException {
        respond(context, response, status, actionStatus, reason, reason);
    }

    /**
     * Refuses the request, recording {@code reason} and telling the caller {@code clientMessage}.
     *
     * <p>The two differ where the reason names a repository the caller did not: the node cache is shared between users,
     * so naming what an ID resolved to would answer a question the caller's own token could not. The audit record keeps
     * the full reason.
     */
    private static void respond(
            ScmApiRequestContext context,
            HttpServletResponse response,
            int status,
            ScmApiActionStatus actionStatus,
            String reason,
            String clientMessage)
            throws IOException {
        log.debug("SCM API proxy request refused ({}): {}", actionStatus, reason);
        if (context != null) {
            context.setStatus(actionStatus);
            context.setReason(reason);
        }
        ScmApiErrorResponse.write(response, status, clientMessage);
    }
}
