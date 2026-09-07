package com.rbc.fogwall.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Runtime configuration for per-commit validation: identity verification, author email rules, and commit message
 * blocking.
 *
 * <p>Push-level checks (diff content scanning and secret scanning) live in {@link DiffScanConfig} and
 * {@link SecretScanConfig} respectively.
 *
 * <p>Hot-reloadable via {@code POST /api/config/reload?section=commit}.
 */
@Data
@Builder
public class CommitConfig {

    /** Per-check mode for identity verification. */
    public enum CommitAttributionPolicyMode {
        /** Block the push when the email is not registered to the push user. */
        STRICT,
        /** Warn the push user but allow the push through. */
        WARN,
        /** Skip this check entirely. */
        OFF;

        public static CommitAttributionPolicyMode fromString(String value) {
            if (value == null) return WARN;
            return switch (value.trim().toLowerCase()) {
                case "strict" -> STRICT;
                case "off" -> OFF;
                default -> WARN;
            };
        }
    }

    /**
     * Independent mode settings for each identity check. Committer email is the primary mechanism for linking commit
     * data back to a fogwall identity — it identifies who last touched the commit object (or the rebaser/amender).
     * Author email is attribution metadata and should not gate push access for most workflows.
     */
    @Data
    @Builder
    public static class CommitAttributionPolicyConfig {

        /** Check that each commit's committer email is registered to the push user. Default: {@code WARN}. */
        @Builder.Default
        private CommitAttributionPolicyMode committer = CommitAttributionPolicyMode.WARN;

        /** Check that each commit's author email is registered to the push user. Default: {@code OFF}. */
        @Builder.Default
        private CommitAttributionPolicyMode author = CommitAttributionPolicyMode.OFF;

        /** {@code true} when both checks are off — used to short-circuit resolver calls. */
        public boolean isEffectivelyOff() {
            return committer == CommitAttributionPolicyMode.OFF && author == CommitAttributionPolicyMode.OFF;
        }
    }

    /**
     * Per-check identity verification configuration. Committer defaults to {@code WARN}; author defaults to {@code OFF}
     * so rebase workflows (which preserve the original author email) are not blocked by default.
     */
    @Builder.Default
    private CommitAttributionPolicyConfig attributionPolicy =
            CommitAttributionPolicyConfig.builder().build();

    /** Configuration for author email validation. */
    @Builder.Default
    private AuthorConfig author = AuthorConfig.builder().build();

    /** Configuration for committer email validation. */
    @Builder.Default
    private CommitterConfig committer = CommitterConfig.builder().build();

    /** Configuration for commit message validation. */
    @Builder.Default
    private MessageConfig message = MessageConfig.builder().build();

    /** Configuration for commit-trailer policy (DCO {@code Signed-off-by}, {@code Co-authored-by}). */
    @Builder.Default
    private TrailerPolicyConfig trailers = TrailerPolicyConfig.builder().build();

    /** Configuration for author validation. */
    @Data
    @Builder
    public static class AuthorConfig {

        /** Configuration for email validation. */
        @Builder.Default
        private EmailConfig email = EmailConfig.builder().build();
    }

    /** Configuration for committer validation. */
    @Data
    @Builder
    public static class CommitterConfig {

        /** Configuration for email validation. */
        @Builder.Default
        private EmailConfig email = EmailConfig.builder().build();
    }

    /**
     * An email-match policy: an ordered list of {@link EmailRule}s applied to an author/committer/co-author email.
     * Symmetric allow/block across every dimension (domain, local part, full address), literal or regex per rule
     * (fogwall#146).
     */
    @Data
    @Builder
    public static class EmailConfig {

        /** The rules, evaluated as: any block match rejects; if any allow rule exists, a match is required to pass. */
        @Builder.Default
        private List<EmailRule> rules = new ArrayList<>();

        /** {@code true} when at least one rule is configured (nothing to enforce otherwise). */
        public boolean isConfigured() {
            return rules != null && !rules.isEmpty();
        }

        /**
         * Evaluate an email against this policy.
         *
         * @return a short reason the email is rejected, or {@code null} when it is allowed (or no policy is configured)
         */
        public String violationReason(String email) {
            if (!isConfigured()) {
                return null;
            }
            if (email == null || email.isEmpty()) {
                return "empty email";
            }
            int at = email.lastIndexOf('@');
            if (at < 0) {
                return "missing @ in email";
            }
            String local = email.substring(0, at);
            String domain = email.substring(at + 1);

            boolean anyAllow = false;
            boolean matchedAllow = false;
            for (EmailRule rule : rules) {
                boolean matches = rule.matches(local, domain, email);
                if (rule.getAction() == EmailRule.Action.BLOCK) {
                    if (matches) {
                        return "blocked by policy (" + rule.describe() + ")";
                    }
                } else { // ALLOW
                    anyAllow = true;
                    matchedAllow |= matches;
                }
            }
            if (anyAllow && !matchedAllow) {
                return "not in allowlist";
            }
            return null;
        }
    }

    /** Configuration for commit message validation. */
    @Data
    @Builder
    public static class MessageConfig {

        /** Configuration for blocking specific message patterns. */
        @Builder.Default
        private BlockConfig block = BlockConfig.builder().build();
    }

    /**
     * Commit-trailer policy (fogwall#146). Two independent controls, both enforce-or-off:
     *
     * <ul>
     *   <li><b>DCO</b> — require every commit to carry a {@code Signed-off-by} trailer, optionally one whose email
     *       matches the commit author (see {@link SignedOffByConfig}).
     *   <li><b>Co-author</b> — ban, allowlist (by email filter), or require the {@code Co-authored-by} trailer (see
     *       {@link CoAuthoredByConfig}).
     * </ul>
     */
    @Data
    @Builder
    public static class TrailerPolicyConfig {

        /** DCO / {@code Signed-off-by} policy. */
        @Builder.Default
        private SignedOffByConfig signedOffBy = SignedOffByConfig.builder().build();

        /** {@code Co-authored-by} policy. */
        @Builder.Default
        private CoAuthoredByConfig coAuthoredBy = CoAuthoredByConfig.builder().build();

        /** {@code true} when neither control is active — used to short-circuit the check entirely. */
        public boolean isEffectivelyOff() {
            return !signedOffBy.isRequire() && coAuthoredBy.getPolicy() == CoAuthorPolicy.OFF;
        }
    }

    /** DCO {@code Signed-off-by} policy. */
    @Data
    @Builder
    public static class SignedOffByConfig {

        /** Require each commit to carry at least one {@code Signed-off-by} trailer. Default {@code false}. */
        @Builder.Default
        private boolean require = false;

        /**
         * When {@link #require} is set, also require at least one {@code Signed-off-by} trailer whose email equals the
         * commit's author email — the DCO's "sign off your own work" rule. Default {@code false} (any sign-off
         * satisfies the requirement).
         */
        @Builder.Default
        private boolean requireAuthorMatch = false;
    }

    /** Policy applied to the {@code Co-authored-by} trailer. */
    public enum CoAuthorPolicy {
        /** No restriction. */
        OFF,
        /** Reject any commit that carries a {@code Co-authored-by} trailer. */
        BAN,
        /** Reject a {@code Co-authored-by} whose email is not permitted by the email filter. */
        ALLOWLIST,
        /** Reject any commit that carries no {@code Co-authored-by} trailer. */
        REQUIRE;

        public static CoAuthorPolicy fromString(String value) {
            if (value == null) return OFF;
            return switch (value.trim().toLowerCase()) {
                case "ban" -> BAN;
                case "allowlist" -> ALLOWLIST;
                case "require" -> REQUIRE;
                default -> OFF;
            };
        }
    }

    /** {@code Co-authored-by} policy and its allowlist email filter. */
    @Data
    @Builder
    public static class CoAuthoredByConfig {

        /** How the {@code Co-authored-by} trailer is policed. Default {@link CoAuthorPolicy#OFF}. */
        @Builder.Default
        private CoAuthorPolicy policy = CoAuthorPolicy.OFF;

        /**
         * Email domain-allow / local-block filter applied to each co-author's email under
         * {@link CoAuthorPolicy#ALLOWLIST}. Reuses the same shape as author/committer email rules so operators express
         * "co-authors must be {@code @company.com} or {@code noreply@anthropic.com}" the same way everywhere.
         */
        @Builder.Default
        private EmailConfig email = EmailConfig.builder().build();
    }

    /** Create a default configuration with no restrictions. */
    public static CommitConfig defaultConfig() {
        return CommitConfig.builder().build();
    }
}
