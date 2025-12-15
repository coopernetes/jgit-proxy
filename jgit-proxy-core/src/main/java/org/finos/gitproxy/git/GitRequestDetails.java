package org.finos.gitproxy.git;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.filter.GitProxyFilter;

@Data
public class GitRequestDetails {
    private UUID id = UUID.randomUUID();
    private Instant timestamp = Instant.now();
    private HttpOperation operation;
    private Repository repository;
    private String branch; // null for fetch requests
    private Commit commit;
    private GitProxyProvider provider;
    private List<GitProxyFilter> filters = new ArrayList<>();
    private GitResult result = GitResult.PENDING;
    private String reason;

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
        ACCEPTED, // for async where a push or fetch is accepted but not yet complete
        ERROR;
    }
}
