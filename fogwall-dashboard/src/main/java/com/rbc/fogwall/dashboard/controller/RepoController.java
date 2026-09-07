package com.rbc.fogwall.dashboard.controller;

import com.rbc.fogwall.db.FetchStore;
import com.rbc.fogwall.db.FetchStore.RepoFetchSummary;
import com.rbc.fogwall.db.PushStore;
import com.rbc.fogwall.db.PushStore.RepoPushSummary;
import com.rbc.fogwall.db.UrlRuleRegistry;
import com.rbc.fogwall.db.model.AccessRule;
import com.rbc.fogwall.git.HttpOperation;
import com.rbc.fogwall.git.RepoPath;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.ProviderRegistry;
import com.rbc.fogwall.servlet.filter.UrlRuleEvaluator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Repos", description = "Access control rules and repository traffic")
@Slf4j
@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {

    private final UrlRuleRegistry urlRuleRegistry;

    private final FetchStore fetchStore;

    private final PushStore pushStore;

    private final ProviderRegistry providerSource;

    @Operation(operationId = "listRules", summary = "List access control rules")
    @GetMapping("/rules")
    public List<AccessRule> listRules() {
        return urlRuleRegistry.findAll();
    }

    @Operation(operationId = "getRule", summary = "Get an access control rule")
    @GetMapping("/rules/{id}")
    public ResponseEntity<AccessRule> getRule(@PathVariable String id) {
        return urlRuleRegistry
                .findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "createRule", summary = "Create an access control rule")
    @PostMapping("/rules")
    public ResponseEntity<?> createRule(@RequestBody AccessRule rule) {
        if (rule.getProvider() != null && !rule.getProvider().isBlank()) {
            ResponseEntity<?> err = validateProviderId(rule.getProvider());
            if (err != null) return err;
        }
        rule.setSource(AccessRule.Source.DB);
        urlRuleRegistry.save(rule);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String login = auth != null ? auth.getName() : "unknown";
        log.info("Access rule created by login={}: id={}", login, rule.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    @Operation(operationId = "updateRule", summary = "Update an access control rule")
    @PutMapping("/rules/{id}")
    public ResponseEntity<?> updateRule(@PathVariable String id, @RequestBody AccessRule rule) {
        if (urlRuleRegistry.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (rule.getProvider() != null && !rule.getProvider().isBlank()) {
            ResponseEntity<?> err = validateProviderId(rule.getProvider());
            if (err != null) return err;
        }
        rule.setId(id);
        urlRuleRegistry.update(rule);
        return ResponseEntity.ok(rule);
    }

    @Operation(
            operationId = "testRules",
            summary = "Test which access control rule matches a repository reference",
            description =
                    "Read-only evaluation against the live ruleset. Returns the full ordered trail of enabled rules "
                            + "considered, which one (if any) matched first, and the resulting decision.")
    @PostMapping("/rules/test")
    public ResponseEntity<?> testRules(@RequestBody RuleTestRequest req) {
        if (req.provider() == null || req.provider().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        }
        if (req.owner() == null
                || req.owner().isBlank()
                || req.name() == null
                || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "owner and name are required"));
        }
        ResponseEntity<?> err = validateProviderId(req.provider());
        if (err != null) return err;

        HttpOperation operation;
        try {
            operation = req.operation() != null
                    ? HttpOperation.valueOf(req.operation().toUpperCase())
                    : HttpOperation.PUSH;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid operation: " + req.operation()));
        }

        // Derive the same way the live push and proxy paths do, so the trail an operator sees is the decision their
        // next push gets rather than a near-miss built from a differently-shaped slug.
        Optional<RepoPath> repoPath = RepoPath.parse(req.owner() + "/" + req.name());
        if (repoPath.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "owner and name do not form a valid repository path"));
        }

        FogwallProvider provider = providerSource.getProvider(req.provider()).orElseThrow();
        UrlRuleEvaluator evaluator = new UrlRuleEvaluator(urlRuleRegistry, provider);
        UrlRuleEvaluator.Trail trail = evaluator.evaluateTrail(
                repoPath.get().slug(), repoPath.get().owner(), repoPath.get().name(), operation);

        List<RuleTestStep> steps = trail.steps().stream()
                .map(s -> new RuleTestStep(
                        s.rule().getId(),
                        s.rule().getRuleOrder(),
                        s.rule().getAccess().name(),
                        s.rule().getDescription(),
                        s.matched()))
                .toList();

        String decision =
                switch (trail.result()) {
                    case UrlRuleEvaluator.Result.Allowed _ -> "ALLOW";
                    case UrlRuleEvaluator.Result.Denied _ -> "DENY";
                    case UrlRuleEvaluator.Result.NotAllowed _ -> "NOT_ALLOWED";
                };
        String matchedRuleId =
                switch (trail.result()) {
                    case UrlRuleEvaluator.Result.Allowed a -> a.ruleId();
                    case UrlRuleEvaluator.Result.Denied d -> d.ruleId();
                    case UrlRuleEvaluator.Result.NotAllowed _ -> null;
                };

        return ResponseEntity.ok(new RuleTestResponse(decision, matchedRuleId, steps));
    }

    /**
     * Returns a 400 response if {@code providerId} is not a configured provider name, or {@code null} if it is valid.
     */
    private ResponseEntity<?> validateProviderId(String providerId) {
        Set<String> known = providerSource.getProviders().stream()
                .map(FogwallProvider::getProviderId)
                .collect(Collectors.toSet());
        if (!known.contains(providerId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown provider '" + providerId + "'. Must be one of: " + known));
        }
        return null;
    }

    @Operation(operationId = "deleteRule", summary = "Delete an access control rule")
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        var existing = urlRuleRegistry.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        urlRuleRegistry.delete(id);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String login = auth != null ? auth.getName() : "unknown";
        log.info("Access rule deleted by login={}: id={}", login, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Active repos view — aggregates push records and fetch records by repo, showing observed traffic regardless of
     * access rule configuration.
     */
    @Operation(operationId = "listActiveRepos", summary = "List repositories with observed push/fetch traffic")
    @GetMapping("/active")
    public List<Map<String, Object>> activeRepos() {
        // Keyed by "provider|owner|repoName"
        Map<String, Map<String, Object>> byRepo = new HashMap<>();

        // Aggregate push records using a single SQL GROUP BY query.
        for (RepoPushSummary summary : pushStore.summarizeByRepo()) {
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
            entry.put("pushCount", summary.total());
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
            entry.put("fetchCount", summary.total() - summary.blocked());
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

    public record RuleTestRequest(String provider, String owner, String name, String operation) {}

    public record RuleTestStep(String ruleId, int order, String access, String description, boolean matched) {}

    public record RuleTestResponse(String decision, String matchedRuleId, List<RuleTestStep> steps) {}
}
