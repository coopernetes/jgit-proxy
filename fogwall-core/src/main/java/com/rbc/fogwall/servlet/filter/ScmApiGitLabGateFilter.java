package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.provider.GitLabProvider;
import com.rbc.fogwall.scmapi.GitLabProjectIdResolver;
import com.rbc.fogwall.scmapi.GitLabRestAllowlist;
import com.rbc.fogwall.scmapi.GitLabTargetProject;
import com.rbc.fogwall.scmapi.OwnerRepo;
import com.rbc.fogwall.scmapi.ScmApiRestMatch;
import com.rbc.fogwall.servlet.PushTooLargeException;
import com.rbc.fogwall.servlet.RequestBodyWrapper;
import com.rbc.fogwall.servlet.ScmApiErrorResponse;
import com.rbc.fogwall.servlet.ScmApiQueryPolicy;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import com.rbc.fogwall.servlet.ScmApiRestPath;
import com.rbc.fogwall.servlet.ScmApiRestPathPolicy;
import com.rbc.fogwall.servlet.ScmApiTokenExtractor;
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
 * The SCM API proxy decision pipeline for GitLab's REST dialect; see {@link ScmApiGitHubGateFilter} for GraphQL. GitLab
 * addresses its target directly in the URL as a URL-encoded {@code owner/repo} path segment (verified from live
 * {@code glab} captures — see docs/internals/SCM_API_PROXY.md's GitLab section), so the matched path segment is the
 * authorization target, with no opaque-ID resolution step.
 *
 * <p>Reads (any {@code GET}) are gated by authentication alone — no allowlist, no permission check. Any non-GET request
 * not matching {@link GitLabRestAllowlist} is denied fail-closed.
 */
@Slf4j
@RequiredArgsConstructor
public class ScmApiGitLabGateFilter implements Filter {

    private final GitLabProvider provider;
    private final GitLabProjectIdResolver projectIdResolver;
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
        String method = httpRequest.getMethod();
        String path = ScmApiRestPath.rawSubPath(httpRequest);

        // Checked ahead of the read/mutate split so it covers GETs too, which skip the allowlist.
        if (!ScmApiRestPathPolicy.isForwardable(path, ScmApiRestPathPolicy.EncodedSeparators.GITLAB_PROJECT_SEGMENT)) {
            fail(context, httpResponse, HttpServletResponse.SC_BAD_REQUEST, "Malformed request path");
            return;
        }

        // A request whose shape no supported CLI produces is refused before anything downstream reads what it
        // carries. The forwarder checks again, as it does the path.
        String refusedParameter =
                ScmApiQueryPolicy.refusedParameter(httpRequest.getQueryString(), !"GET".equalsIgnoreCase(method));
        if (refusedParameter != null) {
            respond(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_BAD_REQUEST,
                    ScmApiActionStatus.DENIED,
                    "Query parameter '" + refusedParameter + "' is not permitted on this request");
            return;
        }

        if ("GET".equalsIgnoreCase(method)) {
            handleRead(httpResponse, chain, wrapper);
            return;
        }

        Optional<ScmApiRestMatch> match = GitLabRestAllowlist.match(method, path);
        if (match.isEmpty()) {
            deny(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Operation '" + method + " " + path + "' is not allowlisted");
            return;
        }

        String operation = match.get().operation();
        context.setMutationField(operation);

        Optional<OwnerRepo> target =
                authorizationTarget(httpRequest, wrapper, match.get().ownerRepo());
        if (target.isEmpty()) {
            fail(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Merge request names a target project that could not be resolved to a repository");
            return;
        }
        OwnerRepo ownerRepo = target.get();
        context.setRepoOwner(ownerRepo.owner());
        context.setRepoName(ownerRepo.name());

        String repoPath = "/" + ownerRepo.owner() + "/" + ownerRepo.name();
        if (!repoPermissionService.isAllowedToPropose(context.getResolvedUser(), provider.getProviderId(), repoPath)) {
            denyWithoutNamingTarget(
                    context,
                    httpResponse,
                    HttpServletResponse.SC_FORBIDDEN,
                    "User '" + context.getResolvedUser() + "' is not permitted to perform API mutations on "
                            + repoPath);
            return;
        }

        chain.doFilter(wrapper, response);
    }

    /**
     * The repository this request must be authorized against: the body's {@code target_project_id} when one is present,
     * otherwise the project named in the URL.
     *
     * <p>This is the fork case. {@code glab mr create} posts to the <b>source</b> project and names the upstream only
     * in the body, so the URL alone would authorize the fork — which the contributor owns and can always write to —
     * instead of the upstream the merge request is opened on. Empty means deny: a target that is named but cannot be
     * resolved is exactly when falling back to the URL would check the wrong repository.
     */
    private Optional<OwnerRepo> authorizationTarget(
            HttpServletRequest request, RequestBodyWrapper wrapper, OwnerRepo fromUrl) throws IOException {
        return switch (GitLabTargetProject.targetProjectId(wrapper.getBody())) {
            case GitLabTargetProject.Result.Absent() -> Optional.of(fromUrl);
            case GitLabTargetProject.Result.Unusable(String reason) -> {
                log.warn("Refusing GitLab merge request: {}", reason);
                yield Optional.empty();
            }
            case GitLabTargetProject.Result.Present(String projectId) -> {
                String authHeader = ScmApiTokenExtractor.authHeaderName(request);
                yield projectIdResolver.resolve(
                        provider, projectId, authHeader, authHeader == null ? null : request.getHeader(authHeader));
            }
        };
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
