package org.finos.gitproxy.dashboard.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/push")
public class PushController {

    @Autowired
    private PushStore pushStore;

    /**
     * List push records. Optional query params: status, project, repo, user, search (matches project OR repo name),
     * limit (default 50).
     */
    @GetMapping
    public List<PushRecord> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "50") int limit) {

        PushQuery.PushQueryBuilder query = PushQuery.builder().limit(limit).newestFirst(true);

        if (status != null && !status.isBlank()) {
            try {
                query.status(PushStatus.valueOf(status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown status: " + status + ". Valid values: " + List.of(PushStatus.values()));
            }
        }
        if (project != null && !project.isBlank()) query.project(project);
        if (repo != null && !repo.isBlank()) query.repoName(repo);
        if (user != null && !user.isBlank()) query.user(user);
        if (search != null && !search.isBlank()) query.search(search);

        return pushStore.find(query.build());
    }

    /**
     * Look up a push record by its commit reference ({commitFrom}_{commitTo}). Used by the transparent proxy flow where
     * we link to a push before it has been saved with a UUID.
     */
    @GetMapping("/by-ref/{ref}")
    public ResponseEntity<PushRecord> getByRef(@PathVariable String ref) {
        // ref format: {commitFrom}_{commitTo} (may be short 8-char SHAs)
        String[] parts = ref.split("_", 2);
        if (parts.length != 2) {
            return ResponseEntity.badRequest().build();
        }
        String commitTo = parts[1];

        // Find by commitTo (most selective) - pick the most recent BLOCKED or APPROVED record
        List<PushRecord> records = pushStore.find(PushQuery.builder()
                .commitTo(commitTo)
                .newestFirst(true)
                .limit(1)
                .build());

        // Fall back to full SHA lookup if short SHA was used
        if (records.isEmpty()) {
            records = pushStore.find(
                    PushQuery.builder().newestFirst(true).limit(50).build());
            records = records.stream()
                    .filter(r -> r.getCommitTo() != null && r.getCommitTo().startsWith(commitTo))
                    .limit(1)
                    .collect(Collectors.toList());
        }

        return records.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(records.get(0));
    }

    /** Get a single push record by ID. */
    @GetMapping("/{id}")
    public ResponseEntity<PushRecord> getById(@PathVariable String id) {
        return pushStore
                .findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Approve a push. Body: { "reviewerUsername": "...", "reviewerEmail": "...", "reason": "..." } */
    @PostMapping("/{id}/authorise")
    public ResponseEntity<?> approve(@PathVariable String id, @RequestBody Map<String, String> body) {
        return pushStore
                .findById(id)
                .map(record -> {
                    if (record.getStatus() != PushStatus.BLOCKED) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Push is not in BLOCKED status: " + record.getStatus()));
                    }
                    var attestation = Attestation.builder()
                            .pushId(id)
                            .type(Attestation.Type.APPROVAL)
                            .reviewerUsername(body.getOrDefault("reviewerUsername", "system"))
                            .reviewerEmail(body.get("reviewerEmail"))
                            .reason(body.get("reason"))
                            .build();
                    var updated = pushStore.approve(id, attestation);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** Reject a push. Body: { "reviewerUsername": "...", "reviewerEmail": "...", "reason": "..." } (reason required) */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id, @RequestBody Map<String, String> body) {
        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Rejection reason is required"));
        }
        return pushStore
                .findById(id)
                .map(record -> {
                    if (record.getStatus() != PushStatus.BLOCKED) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Push is not in BLOCKED status: " + record.getStatus()));
                    }
                    var attestation = Attestation.builder()
                            .pushId(id)
                            .type(Attestation.Type.REJECTION)
                            .reviewerUsername(body.getOrDefault("reviewerUsername", "system"))
                            .reviewerEmail(body.get("reviewerEmail"))
                            .reason(reason)
                            .build();
                    var updated = pushStore.reject(id, attestation);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** Cancel a push. Body: { "reviewerUsername": "..." } */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return pushStore
                .findById(id)
                .map(record -> {
                    if (record.getStatus() != PushStatus.BLOCKED) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Push is not in BLOCKED status: " + record.getStatus()));
                    }
                    var attestation = Attestation.builder()
                            .pushId(id)
                            .type(Attestation.Type.CANCELLATION)
                            .reviewerUsername(body != null ? body.getOrDefault("reviewerUsername", "system") : "system")
                            .build();
                    var updated = pushStore.cancel(id, attestation);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
