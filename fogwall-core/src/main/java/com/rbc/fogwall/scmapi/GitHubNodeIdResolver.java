package com.rbc.fogwall.scmapi;

import com.rbc.fogwall.net.FogwallHttpExecutor;
import com.rbc.fogwall.provider.GitHubProvider;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves an opaque GitHub GraphQL node ID to the {@code owner/repo} it belongs to, so the SCM API proxy's
 * authorization step has a concrete permission-check target — mutations reference their subject only by node ID, never
 * by owner/repo (docs/internals/SCM_API_PROXY.md).
 *
 * <p>GitHub-specific: the {@code node(id:)} query below is GitHub's own schema shape. GitLab's counterpart is
 * {@link GitLabProjectIdResolver}.
 */
@Slf4j
public class GitHubNodeIdResolver {

    private static final String NODE_QUERY = "query($id: ID!) { node(id: $id) {"
            + " ... on Repository { name owner { login } }"
            + " ... on Issue { repository { name owner { login } } }"
            + " ... on PullRequest { repository { name owner { login } } } } }";

    private static final JsonMapper MAPPER = new JsonMapper();

    /**
     * This call sits inline on a mutation, so an upstream that accepts the connection and then stalls would otherwise
     * hold a request thread until the container gave up. Matches the bound the provider lookups already use.
     */
    private static final Timeout RESOLVE_TIMEOUT = Timeout.ofSeconds(10);

    private final GitHubNodeIdCache cache;

    public GitHubNodeIdResolver(GitHubNodeIdCache cache) {
        this.cache = cache;
    }

    /**
     * Resolves {@code ref} to the repository it belongs to, using {@code callerToken} — the CLI caller's own upstream
     * credential; fogwall never uses its own credential for this lookup, per the BYO-token model. Cache first; only
     * calls upstream on a miss.
     */
    public Optional<OwnerRepo> resolve(GitHubProvider provider, MutationNodeIdRef ref, String callerToken) {
        Optional<OwnerRepo> cached = cache.lookup(provider.getProviderId(), ref.nodeId());
        if (cached.isPresent()) {
            return cached;
        }
        Optional<OwnerRepo> resolved = resolveUpstream(provider, ref.nodeId(), callerToken);
        resolved.ifPresent(ownerRepo -> cache.store(provider.getProviderId(), ref.nodeId(), ownerRepo));
        return resolved;
    }

    private Optional<OwnerRepo> resolveUpstream(GitHubProvider provider, String nodeId, String callerToken) {
        try {
            String body = MAPPER.writeValueAsString(Map.of("query", NODE_QUERY, "variables", Map.of("id", nodeId)));
            Request request =
                    Request.post(provider.getGraphqlUrl()).addHeader("Authorization", "Bearer " + callerToken);
            ScmApiUserAgent.self(request);
            String response = request.bodyString(body, ContentType.APPLICATION_JSON)
                    .connectTimeout(RESOLVE_TIMEOUT)
                    .responseTimeout(RESOLVE_TIMEOUT)
                    .execute(FogwallHttpExecutor.instance())
                    .returnContent()
                    .asString();
            return extractOwnerRepo(MAPPER.readTree(response).path("data").path("node"));
        } catch (Exception e) {
            log.warn(
                    "Failed to resolve node ID '{}' for provider '{}': {}",
                    nodeId,
                    provider.getProviderId(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code node} is either a Repository directly, or an Issue/PullRequest wrapping one under "repository". */
    private static Optional<OwnerRepo> extractOwnerRepo(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        JsonNode repo = node.has("repository") ? node.get("repository") : node;
        JsonNode ownerNode = repo.get("owner");
        JsonNode nameNode = repo.get("name");
        if (ownerNode == null || nameNode == null || !nameNode.isString()) {
            return Optional.empty();
        }
        JsonNode loginNode = ownerNode.get("login");
        if (loginNode == null || !loginNode.isString()) {
            return Optional.empty();
        }
        return Optional.of(new OwnerRepo(loginNode.asString(), nameNode.asString()));
    }
}
