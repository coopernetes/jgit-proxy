package org.finos.gitproxy.git;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.filter.GitProxyFilter;

@Data
public class GitRequestDetails {
    private UUID id = UUID.randomUUID();
    private Instant timestamp = Instant.now();
    private HttpOperation operation;
    private Repository repository;
    private String branch; // null for fetch requests
    private Commit commit; // Head/parent commit from the push
    private String commitFrom; // Old ref SHA from the packet line (the push range start)
    private String commitTo; // New ref SHA from the packet line (the push range end)
    private List<Commit> pushedCommits = new ArrayList<>(); // All commits received in this push
    private GitProxyProvider provider;
    private List<GitProxyFilter> filters = new ArrayList<>();
    private List<PushStep> steps = new ArrayList<>(); // Filter/hook results for audit trail
    private GitResult result = GitResult.PENDING;
    private String reason;

    /** Returns true when this push is a ref deletion (commitTo is the all-zeros null SHA). */
    public boolean isRefDeletion() {
        return commitTo != null && commitTo.matches("^0+$");
    }

    @Builder
    @Getter
    public static class Repository {
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
