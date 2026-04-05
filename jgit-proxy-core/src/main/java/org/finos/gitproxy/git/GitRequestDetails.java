package org.finos.gitproxy.git;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.eclipse.jgit.lib.Repository;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.filter.GitProxyFilter;

@Data
public class GitRequestDetails {
    private UUID id = UUID.randomUUID();
    private Instant timestamp = Instant.now();
    private HttpOperation operation;
    private RepoRef repoRef;
    /**
     * The local JGit repository for this push, populated by {@code EnrichPushCommitsFilter} after cloning/fetching the
     * upstream. Available to all downstream filters - no need to inject {@code LocalRepositoryCache} separately.
     */
    private Repository localRepository;

    private String branch; // null for fetch requests
    private Commit commit; // Head/parent commit from the push
    private String commitFrom; // Old ref SHA from the packet line (the push range start)
    private String commitTo; // New ref SHA from the packet line (the push range end)
    private List<Commit> pushedCommits = new ArrayList<>(); // All commits received in this push
    private GitProxyProvider provider; // this should never be null
    /**
     * Provider-specific upstream username to use when forwarding the push. Set by {@code BitbucketIdentityFilter}
     * (transparent proxy) or {@code BitbucketCredentialRewriteHook} (store-and-forward) when the push username needs
     * rewriting before forwarding. {@code null} for all non-Bitbucket providers.
     */
    private String upstreamUsername;

    /**
     * The resolved proxy username for the push. Set by {@code CheckUserPushPermissionFilter} /
     * {@code CheckUserPushPermissionHook} after identity resolution. Stored as {@code push_user} in the push record so
     * that dashboard "my pushes" filtering works against the proxy login, not the raw HTTP Basic username.
     */
    private String resolvedUser;

    /**
     * The actual SCM username on the upstream provider (e.g. GitHub login "coopernetes"). Populated when the resolved
     * {@link org.finos.gitproxy.user.UserEntry} has a matching SCM identity for the provider. Use this for provider
     * profile links — {@link #resolvedUser} is the proxy account, not the SCM handle.
     */
    private String scmUsername;

    private List<GitProxyFilter> filters = new ArrayList<>();
    private List<PushStep> steps = new ArrayList<>(); // Filter/hook results for audit trail
    private GitResult result = GitResult.PENDING;
    private String reason;

    /** Returns true when this push is a ref deletion (commitTo is the all-zeros null SHA). */
    public boolean isRefDeletion() {
        return commitTo != null && commitTo.matches("^0+$");
    }

    /** Returns true when this push targets a tag ref ({@code refs/tags/} prefix). */
    public boolean isTagPush() {
        return branch != null && branch.startsWith("refs/tags/");
    }

    @Builder
    @Getter
    public static class RepoRef {
        private String owner; // may not be set for all providers
        private String name; // may not be set for all providers
        private String slug;

        @Override
        public String toString() {
            return "{" + "owner='" + owner + '\'' + ", name='" + name + '\'' + ", slug='" + slug + '\'' + '}';
        }
    }

    public enum GitResult {
        PENDING,
        ALLOWED,
        BLOCKED,
        REJECTED, // hard reject with no review queue (transparent proxy mode)
        ACCEPTED, // for async where a push or fetch is accepted but not yet complete
        ERROR;
    }
}
