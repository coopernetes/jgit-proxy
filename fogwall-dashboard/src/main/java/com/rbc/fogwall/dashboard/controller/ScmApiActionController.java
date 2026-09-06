package com.rbc.fogwall.dashboard.controller;

import com.rbc.fogwall.db.ScmApiActionStore;
import com.rbc.fogwall.db.model.ScmApiActionQuery;
import com.rbc.fogwall.db.model.ScmApiActionRecord;
import com.rbc.fogwall.db.model.ScmApiActionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of SCM API proxy audit records — same auditability bar as push records, no workflow actions. */
@Tag(name = "SCM API", description = "SCM API proxy audit records")
@RestController
@RequestMapping("/api/scm-api-actions")
@RequiredArgsConstructor
public class ScmApiActionController {

    private final ScmApiActionStore scmApiActionStore;

    @Operation(
            operationId = "listScmApiActions",
            summary = "List SCM API proxy audit records",
            description =
                    "Returns SCM API proxy mutation records ordered by most recent first. Filter by status (FORWARDED, DENIED, REJECTED, ERROR), provider, resolved user, repo owner/name, or free-text search. Paginate with limit/offset.")
    @GetMapping
    public List<ScmApiActionRecord> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String repoOwner,
            @RequestParam(required = false) String repoName,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "true") boolean newestFirst) {

        ScmApiActionQuery.ScmApiActionQueryBuilder query = ScmApiActionQuery.builder()
                .provider(provider)
                .user(user)
                .repoOwner(repoOwner)
                .repoName(repoName)
                .search(search)
                .limit(limit)
                .offset(offset)
                .newestFirst(newestFirst);

        if (status != null && !status.isBlank()) {
            try {
                query.status(ScmApiActionStatus.valueOf(status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                return List.of();
            }
        }

        return scmApiActionStore.find(query.build());
    }

    @Operation(operationId = "getScmApiAction", summary = "Get a single SCM API proxy audit record")
    @GetMapping("/{id}")
    public ResponseEntity<ScmApiActionRecord> get(@PathVariable String id) {
        return scmApiActionStore
                .findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
