package org.finos.gitproxy.dashboard.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.Attestation;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.jetty.config.AttestationQuestion;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.ProviderConfig;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/push")
public class PushController {

    @Autowired
    private PushStore pushStore;

    @Autowired(required = false)
    private RepoPermissionService repoPermissionService;

    @Autowired
    private GitProxyConfig gitProxyConfig;

    /** Returns the authenticated username, falling back to {@code body.reviewerUsername}, then {@code "system"}. */
    private static String resolveReviewer(Map<String, String> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return body != null ? body.getOrDefault("reviewerUsername", "system") : "system";
    }

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
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "true") boolean newestFirst) {

        PushQuery.PushQueryBuilder query =
                PushQuery.builder().limit(limit).offset(offset).newestFirst(newestFirst);

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

        // Find by commitTo (most selective) - pick the most recent PENDING or APPROVED record
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

    /**
     * Request body for the approve endpoint. Extends the basic reviewer fields with an optional map of attestation
     * question answers keyed by question ID.
     */
    public record ApproveBody(
            String reviewerUsername, String reviewerEmail, String reason, Map<String, String> attestations) {}

    /**
     * Approve a push. Body: { "reviewerUsername": "...", "reviewerEmail": "...", "reason": "...", "attestations": {
     * "question-id": "answer", ... } }
     */
    @PostMapping("/{id}/authorise")
    public ResponseEntity<?> approve(@PathVariable String id, @RequestBody ApproveBody body) {
        return pushStore
                .findById(id)
                .map(record -> {
                    if (record.getStatus() != PushStatus.PENDING) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Push is not in PENDING status: " + record.getStatus()));
                    }
                    ResponseEntity<?> identityError = checkReviewerIdentity(record);
                    if (identityError != null) return identityError;

                    // Validate required attestation questions are answered
                    ResponseEntity<?> attestationError = checkAttestationAnswers(record, body.attestations());
                    if (attestationError != null) return attestationError;

                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    var attestation = Attestation.builder()
                            .pushId(id)
                            .type(Attestation.Type.APPROVAL)
                            .reviewerUsername(resolveReviewerFromApproveBody(body, auth))
                            .reviewerEmail(body.reviewerEmail())
                            .reason(body.reason())
                            .selfApproval(isSelfApproval(record, auth))
                            .answers(body.attestations())
                            .build();
                    var updated = pushStore.approve(id, attestation);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Checks that all required attestation questions for the push's provider have been answered.
     *
     * @return a 400 response if a required question is missing, {@code null} if all required questions are answered
     */
    private ResponseEntity<?> checkAttestationAnswers(PushRecord record, Map<String, String> answers) {
        String providerName = record.getProvider();
        if (providerName == null) return null;
        var providerCfgs = gitProxyConfig.getProviders();
        if (providerCfgs == null) return null;
        ProviderConfig cfg = providerCfgs.get(providerName);
        if (cfg == null || cfg.getAttestationQuestions().isEmpty()) return null;

        Map<String, String> submitted = answers != null ? answers : Map.of();
        for (AttestationQuestion question : cfg.getAttestationQuestions()) {
            if (!question.isRequired()) continue;
            String answer = submitted.get(question.getId());
            if (answer == null || answer.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Required attestation question not answered: " + question.getId()));
            }
            // For checkboxes, "true" is the only accepted value for a required question
            if ("checkbox".equals(question.getType()) && !"true".equals(answer)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Required attestation checkbox must be checked: " + question.getId()));
            }
        }
        return null;
    }

    private static String resolveReviewerFromApproveBody(ApproveBody body, Authentication auth) {
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return body != null && body.reviewerUsername() != null ? body.reviewerUsername() : "system";
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
                    if (record.getStatus() != PushStatus.PENDING) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Push is not in PENDING status: " + record.getStatus()));
                    }
                    ResponseEntity<?> identityError = checkReviewerIdentity(record);
                    if (identityError != null) return identityError;
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    var attestation = Attestation.builder()
                            .pushId(id)
                            .type(Attestation.Type.REJECTION)
                            .reviewerUsername(resolveReviewer(body))
                            .reviewerEmail(body.get("reviewerEmail"))
                            .reason(reason)
                            .selfApproval(isSelfApproval(record, auth))
                            .build();
                    var updated = pushStore.reject(id, attestation);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Validates that the current session user may review the given push record:
     *
     * <ol>
     *   <li>ROLE_ADMIN bypasses all identity checks — admins may approve/reject any push.
     *   <li>The pusher must have been resolved to a proxy user — if not, we cannot guarantee identity.
     *   <li>Self-review: allowed only when the reviewer has both {@code ROLE_SELF_CERTIFY} (the capability, attested by
     *       the org's IdP/IAM via {@code auth.role-mappings} or the local {@code users[].roles} block) and a
     *       {@link org.finos.gitproxy.permission.RepoPermission.Operations#SELF_CERTIFY} repo permission entry for this
     *       specific repository (the per-repo entitlement). Both must be present.
     *   <li>Non-self reviewer: by default any authenticated user may review. When
     *       {@code server.require-review-permission: true}, the user must have a REVIEW (or PUSH_AND_REVIEW) permission
     *       for the repo.
     * </ol>
     *
     * @return a 403 response if the check fails, {@code null} if the reviewer is permitted to proceed
     */
    private ResponseEntity<?> checkReviewerIdentity(PushRecord record) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (isAdmin(auth)) return null;

        String reviewer = auth != null ? auth.getName() : null;
        String pusherProxyUser = record.getResolvedUser();

        if (pusherProxyUser == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(
                            Map.of(
                                    "error",
                                    "Pusher identity has not been resolved to a proxy user; approval requires verified identity"));
        }

        if (pusherProxyUser.equals(reviewer)) {
            // Self-review requires two independent checks:
            // 1. ROLE_SELF_CERTIFY — the capability, granted via auth.role-mappings or users[].roles in config.
            //    This is the pre-requisite gate: it must be attested by the org's IdP/IAM before any per-repo
            //    entitlement can take effect.
            // 2. A SELF_CERTIFY repo permission entry for this specific repo — the per-repo entitlement.
            boolean hasSelfCertifyRole = auth != null
                    && auth.getAuthorities().stream().anyMatch(a -> "ROLE_SELF_CERTIFY".equals(a.getAuthority()));
            if (!hasSelfCertifyRole) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Self-approval is not permitted: SELF_CERTIFY role not granted"));
            }
            boolean hasSelfCertifyPerm = repoPermissionService != null
                    && record.getProvider() != null
                    && record.getUrl() != null
                    && repoPermissionService.isBypassReviewAllowed(reviewer, record.getProvider(), record.getUrl());
            if (hasSelfCertifyPerm) {
                return null;
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error", "Self-approval is not permitted: no SELF_CERTIFY permission for this repository"));
        }

        // Non-self reviewer: check REVIEW permission only when require-review-permission is enabled.
        // Default (false) allows any authenticated user to review any push they did not push themselves.
        boolean requirePerm =
                gitProxyConfig.getServer() != null && gitProxyConfig.getServer().isRequireReviewPermission();
        if (requirePerm && repoPermissionService != null && record.getProvider() != null && record.getUrl() != null) {
            if (reviewer != null
                    && !repoPermissionService.isAllowedToReview(reviewer, record.getProvider(), record.getUrl())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You do not have permission to approve pushes for this repository"));
            }
        }

        return null;
    }

    /** Cancel a push. Only the pusher or an admin may cancel. Body: { "reviewerUsername": "..." } */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return pushStore
                .findById(id)
                .map(record -> {
                    if (record.getStatus() != PushStatus.PENDING) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Push is not in PENDING status: " + record.getStatus()));
                    }
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (!isAdmin(auth)) {
                        String pusherProxyUser = record.getResolvedUser();
                        String reviewer = auth != null ? auth.getName() : null;
                        if (pusherProxyUser == null || !pusherProxyUser.equals(reviewer)) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                    .body(Map.of("error", "Only the pusher or an admin can cancel a push"));
                        }
                    }
                    var attestation = Attestation.builder()
                            .pushId(id)
                            .type(Attestation.Type.CANCELLATION)
                            .reviewerUsername(resolveReviewer(body))
                            .build();
                    var updated = pushStore.cancel(id, attestation);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private static boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static boolean isSelfApproval(PushRecord record, Authentication auth) {
        if (!isAdmin(auth)) {
            // Non-admin self-reviews are permitted only via an explicit SELF_CERTIFY grant —
            // that is expected behaviour, not an admin override.
            return false;
        }
        String pusher = record.getResolvedUser();
        String reviewer = auth != null ? auth.getName() : null;
        return pusher != null && pusher.equals(reviewer);
    }
}
