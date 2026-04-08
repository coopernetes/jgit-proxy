package org.finos.gitproxy.dashboard.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.db.FetchStore;
import org.finos.gitproxy.db.FetchStore.RepoFetchSummary;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/repos")
public class RepoController {

    @Autowired
    private RepoRegistry repoRegistry;

    @Autowired
    private FetchStore fetchStore;

    @Autowired
    private PushStore pushStore;

    /** List all access rules. */
    @GetMapping("/rules")
    public List<AccessRule> listRules() {
        return repoRegistry.findAll();
    }

    /** Get a single access rule by ID. */
    @GetMapping("/rules/{id}")
    public ResponseEntity<AccessRule> getRule(@PathVariable String id) {
        return repoRegistry
                .findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Create a new access rule. */
    @PostMapping("/rules")
    public ResponseEntity<AccessRule> createRule(@RequestBody AccessRule rule) {
        rule.setSource(AccessRule.Source.DB);
        repoRegistry.save(rule);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String login = auth != null ? auth.getName() : "unknown";
        log.info("Access rule created by login={}: id={}", login, rule.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    /** Update an existing access rule. */
    @PutMapping("/rules/{id}")
    public ResponseEntity<AccessRule> updateRule(@PathVariable String id, @RequestBody AccessRule rule) {
        if (repoRegistry.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        rule.setId(id);
        repoRegistry.update(rule);
        return ResponseEntity.ok(rule);
    }

    /** Delete an access rule. */
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        var existing = repoRegistry.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repoRegistry.delete(id);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String login = auth != null ? auth.getName() : "unknown";
        log.info("Access rule deleted by login={}: id={}", login, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Active repos view — aggregates push records and fetch records by repo, showing observed traffic regardless of
     * access rule configuration.
     */
    @GetMapping("/active")
    public List<Map<String, Object>> activeRepos() {
        // Keyed by "provider|owner|repoName"
        Map<String, Map<String, Object>> byRepo = new HashMap<>();

        // Aggregate push records. push_records has no provider column; derive from upstream_url host.
        List<PushRecord> pushRecords =
                pushStore.find(PushQuery.builder().limit(5000).build());
        for (PushRecord pr : pushRecords) {
            String provider = providerFromUrl(pr.getUpstreamUrl());
            String owner = pr.getProject(); // project = owner (see PushRecordMapper)
            String key = provider + "|" + owner + "|" + pr.getRepoName();
            byRepo.computeIfAbsent(key, k -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("provider", provider);
                entry.put("owner", owner);
                entry.put("repoName", pr.getRepoName());
                entry.put("pushCount", 0L);
                entry.put("fetchCount", 0L);
                entry.put("blockedFetchCount", 0L);
                return entry;
            });
            byRepo.get(key).merge("pushCount", 1L, (a, b) -> (long) a + (long) b);
        }

        // Merge fetch summaries
        for (RepoFetchSummary summary : fetchStore.summarizeByRepo()) {
            String key = summary.provider() + "|" + summary.owner() + "|" + summary.repoName();
            Map<String, Object> entry = byRepo.computeIfAbsent(key, k -> {
                Map<String, Object> e = new HashMap<>();
                e.put("provider", summary.provider());
                e.put("owner", summary.owner());
                e.put("repoName", summary.repoName());
                e.put("pushCount", 0L);
                e.put("fetchCount", 0L);
                e.put("blockedFetchCount", 0L);
                return e;
            });
            entry.put("fetchCount", summary.total());
            entry.put("blockedFetchCount", summary.blocked());
        }

        // Sort by total activity descending
        return new ArrayList<>(byRepo.values())
                .stream()
                        .sorted(Comparator.comparingLong((Map<String, Object> e) ->
                                        (long) e.get("pushCount") + (long) e.get("fetchCount"))
                                .reversed())
                        .collect(Collectors.toList());
    }

    private static String providerFromUrl(String url) {
        if (url == null || url.isBlank()) return "unknown";
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
