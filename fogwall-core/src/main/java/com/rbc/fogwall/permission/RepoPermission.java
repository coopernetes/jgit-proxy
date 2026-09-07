package com.rbc.fogwall.permission;

import com.rbc.fogwall.db.model.MatchTarget;
import com.rbc.fogwall.db.model.MatchType;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single authorization grant: {@link #username} is permitted to perform {@link #grant} on repos matching
 * {@link #value} at {@link #provider}.
 *
 * <p>{@link #target} selects which part of the repo URL is compared (default {@link MatchTarget#SLUG});
 * {@link #matchType} controls how {@link #value} is interpreted: {@code GLOB} for {@code *}/{@code ?} wildcards
 * (default), {@code LITERAL} for exact equality, {@code REGEX} for full Java regex.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoPermission implements FogwallPermission {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    private String username;
    private String provider;

    /** Which part of the repository URL is matched. Defaults to {@link MatchTarget#SLUG}. */
    @Builder.Default
    private MatchTarget target = MatchTarget.SLUG;

    /** Pattern to match against the {@link #target} portion of the URL. */
    private String value;

    /** How {@link #value} is interpreted when matching. Defaults to {@link MatchType#GLOB}. */
    @Builder.Default
    private MatchType matchType = MatchType.GLOB;

    @Builder.Default
    private Grant grant = Grant.PUSH;

    @Builder.Default
    private Source source = Source.DB;

    public enum Grant {
        /** Can submit pushes for review. */
        PUSH,
        /** Can review (approve or reject) pushes submitted by others. */
        REVIEW,
        /** Shorthand for {@link #PUSH} + {@link #REVIEW}. Does not include {@link #SELF_CERTIFY}. */
        PUSH_AND_REVIEW,
        /**
         * Trusted contributor: can certify their own clean pushes without a separate peer reviewer. All validation
         * still runs; the automated attestation is recorded in the audit log. Does not imply {@link #PUSH} or
         * {@link #REVIEW} — those must be granted separately if also needed.
         */
        SELF_CERTIFY,
        /**
         * Can propose a change against matching repos — open a pull/merge request and iterate on it through the SCM API
         * proxy, along with the issue operations that accompany it.
         *
         * <p>Scope is the whole request surface of the allowlisted endpoints, not just title, body and comment: every
         * field the supported CLIs send has one of their own flags behind it, and {@code tea} PATCHes the full object
         * on every edit. So this also permits retargeting a proposal's base branch — within the same repository, since
         * no allowlisted edit endpoint takes a repository-valued field. The only effect reaching past the repo is
         * association with an object that is not repo-scoped: a GitHub project, or a GitLab group milestone or epic.
         *
         * <p>Named for what it permits rather than what it achieves: whether a proposal becomes a contribution is the
         * upstream maintainer's call, outside fogwall. Kept distinct from {@link #PUSH} so an operator can permission
         * git-push and change proposals independently — pushing to a fork proposes nothing — and does not imply
         * {@link #PUSH} or {@link #REVIEW}. Reads are not gated by this grant at all; they go through the existing
         * URL-rule mechanism, the same as git fetches.
         *
         * <p>Issue create/edit/comment are deliberately included: filing an issue that a pull request then closes is
         * part of one contribution, and comments cannot be split by subject anyway — GitHub's {@code addComment} takes
         * an "Issue or PR" id, and Gitea posts pull-request comments to its issue endpoint. A narrower participation
         * grant, for people who file issues but propose no code, would be a subset of this one.
         *
         * <p>Merging is <b>not</b> included — that is a maintainer operation with its own design questions. Neither is
         * review, which stays with the SCM's own UI.
         */
        PROPOSE
    }

    public enum Source {
        CONFIG,
        DB
    }
}
