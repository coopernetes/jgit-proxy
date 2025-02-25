package org.finos.gitproxy.git;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.servlet.filter.GitProxyFilter;

@Data
public class GitRequestDetails {
    private UUID id = UUID.randomUUID();
    private Instant timestamp = Instant.now();
    private HttpOperation operation;
    private String owner;
    private String name;
    private String slug;
    private String branch; // null for fetch requests
    private Commit commit;
    private GitProxyProvider provider;
    private List<GitProxyFilter> filters = new ArrayList<>();
    private GitResult result = GitResult.PENDING;
    private String reason;

    public enum GitResult {
        PENDING,
        ALLOWED,
        BLOCKED,
        ACCEPTED, // for async where a push or fetch is accepted but not yet complete
        ERROR;
    }
}
