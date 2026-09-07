package com.rbc.fogwall.permission;

import static org.junit.jupiter.api.Assertions.*;

import com.rbc.fogwall.db.model.MatchTarget;
import com.rbc.fogwall.db.model.MatchType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link RepoPermissionService} using an in-memory store.
 *
 * <p>Covers fail-closed semantics, LITERAL/GLOB path matching, operation scoping, multi-user scenarios, and
 * {@code seedFromConfig}.
 */
class RepoPermissionServiceTest {

    RepoPermissionService svc;

    @BeforeEach
    void setUp() {
        svc = new RepoPermissionService(new InMemoryRepoPermissionStore());
    }

    private RepoPermission grant(String username, String provider, String value) {
        return RepoPermission.builder()
                .username(username)
                .provider(provider)
                .value(value)
                .matchType(MatchType.LITERAL)
                .grant(RepoPermission.Grant.PUSH_AND_REVIEW)
                .source(RepoPermission.Source.DB)
                .build();
    }

    private RepoPermission grant(
            String username, String provider, String value, MatchType matchType, RepoPermission.Grant ops) {
        return RepoPermission.builder()
                .username(username)
                .provider(provider)
                .value(value)
                .matchType(matchType)
                .grant(ops)
                .source(RepoPermission.Source.DB)
                .build();
    }

    // ---- fail-closed: no grants ----

    @Test
    void noGrants_push_denied() {
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    @Test
    void noGrants_approve_denied() {
        assertFalse(svc.isAllowedToReview("alice", "github", "/owner/repo"));
    }

    @Test
    void noGrants_apiWrite_denied() {
        assertFalse(svc.isAllowedToPropose("alice", "github", "/owner/repo"));
    }

    // ---- literal match: user present ----

    @Test
    void literalGrant_correctUser_push_allowed() {
        svc.save(grant("alice", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    @Test
    void literalGrant_wrongUser_push_denied() {
        svc.save(grant("alice", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("bob", "github", "/owner/repo"));
    }

    // ---- fail-closed: path exists for provider but no user matches ----

    @Test
    void pathExistsButNoUserMatch_denied() {
        // Bob has access; Alice does not — deny Alice even though the path is managed
        svc.save(grant("bob", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    // ---- operation scoping ----

    @Test
    void pushOnlyGrant_allowsPush_deniesApprove() {
        svc.save(grant("alice", "github", "/owner/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToReview("alice", "github", "/owner/repo"));
    }

    @Test
    void approveOnlyGrant_allowsApprove_deniesPush() {
        svc.save(grant("alice", "github", "/owner/repo", MatchType.LITERAL, RepoPermission.Grant.REVIEW));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToReview("alice", "github", "/owner/repo"));
    }

    @Test
    void allGrant_allowsBothOperations() {
        svc.save(grant("alice", "github", "/owner/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToReview("alice", "github", "/owner/repo"));
    }

    @Test
    void apiWriteOnlyGrant_allowsApiWrite_deniesPushAndReview() {
        svc.save(grant("alice", "github", "/owner/repo", MatchType.LITERAL, RepoPermission.Grant.PROPOSE));
        assertTrue(svc.isAllowedToPropose("alice", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToReview("alice", "github", "/owner/repo"));
    }

    @Test
    void pushAndReviewGrant_doesNotImplyApiWrite() {
        svc.save(grant("alice", "github", "/owner/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertFalse(svc.isAllowedToPropose("alice", "github", "/owner/repo"));
    }

    // ---- provider isolation ----

    @Test
    void grantForDifferentProvider_doesNotAllow() {
        svc.save(grant("alice", "gitlab", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    // ---- glob matching ----

    @Test
    void globGrant_matchesAllReposUnderOwner_allowed() {
        svc.save(grant("alice", "github", "/owner/*", MatchType.GLOB, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo-a"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo-b"));
    }

    @Test
    void globGrant_doesNotMatchOtherOwner() {
        svc.save(grant("alice", "github", "/owner/*", MatchType.GLOB, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertFalse(svc.isAllowedToPush("alice", "github", "/other/repo"));
    }

    @Test
    void globGrant_doubleWildcard_matchesAnyPath() {
        svc.save(grant("alice", "github", "/**", MatchType.GLOB, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/other/thing"));
    }

    // ---- glob matching semantics ----
    //
    // Paths use the /owner/repo convention. Glob matching uses java.nio.file.FileSystem#getPathMatcher
    // ("glob:" prefix). Key rules:
    //   * = any sequence of characters within ONE path segment (no "/" crossing)
    //   ** = any sequence including path separators (matches across segments)
    //   ? = exactly one character (no "/" crossing)
    //   Hyphens, dots, and digits in names are regular characters — no special treatment.

    @Test
    void glob_singleStar_matchesRepoName() {
        svc.save(grant("alice", "github", "/acme/*", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/repo"));
    }

    @Test
    void glob_singleStar_matchesHyphenatedName() {
        svc.save(grant("alice", "github", "/acme/*", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/my-service"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/repo-v2"));
    }

    @Test
    void glob_singleStar_doesNotCrossPathSeparator() {
        svc.save(grant("alice", "github", "/acme/*", MatchType.GLOB, RepoPermission.Grant.PUSH));
        // /acme/sub/repo has two segments after /acme — single * does not match
        assertFalse(svc.isAllowedToPush("alice", "github", "/acme/sub/repo"));
    }

    @Test
    void glob_singleStar_doesNotMatchOtherOwner() {
        svc.save(grant("alice", "github", "/acme/*", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertFalse(svc.isAllowedToPush("alice", "github", "/other/repo"));
    }

    @Test
    void glob_doubleStar_matchesAcrossSegments() {
        svc.save(grant("alice", "github", "/acme/**", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/sub/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/a/b/c"));
    }

    @Test
    void glob_doubleStar_doesNotMatchOtherOwner() {
        svc.save(grant("alice", "github", "/acme/**", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertFalse(svc.isAllowedToPush("alice", "github", "/other/repo"));
    }

    @Test
    void glob_leadingDoubleStar_matchesAllPaths() {
        svc.save(grant("alice", "github", "/**", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/other/thing"));
    }

    @Test
    void glob_wildcardOwner_matchesSpecificRepo() {
        svc.save(grant("alice", "github", "/*/repo", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/other/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/acme/other-repo"));
    }

    @Test
    void glob_prefixSuffix_matchesNames() {
        svc.save(grant("alice", "github", "/acme/service-*", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/service-api"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/service-worker"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/acme/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/acme/my-service-api"));
    }

    @Test
    void glob_questionMark_matchesSingleChar() {
        svc.save(grant("alice", "github", "/acme/repo-?", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/repo-1"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/acme/repo-a"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/acme/repo-12"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/acme/repo-"));
    }

    // ---- match target: OWNER / NAME (previously ignored — always compared against the full slug) ----

    private RepoPermission targetGrant(
            String username, String provider, MatchTarget target, String value, MatchType matchType) {
        return RepoPermission.builder()
                .username(username)
                .provider(provider)
                .target(target)
                .value(value)
                .matchType(matchType)
                .grant(RepoPermission.Grant.PUSH_AND_REVIEW)
                .source(RepoPermission.Source.DB)
                .build();
    }

    @Test
    void ownerTarget_literal_matchesAllReposUnderOwner() {
        svc.save(targetGrant("alice", "github", MatchTarget.OWNER, "myorg", MatchType.LITERAL));
        assertTrue(svc.isAllowedToPush("alice", "github", "/myorg/anything"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/myorg/other-repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/other/anything"));
    }

    @Test
    void ownerTarget_glob_matchesOwnerPrefix() {
        svc.save(targetGrant("alice", "github", MatchTarget.OWNER, "team-*", MatchType.GLOB));
        assertTrue(svc.isAllowedToPush("alice", "github", "/team-alpha/x"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/team-beta/y"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/other/x"));
    }

    @Test
    void nameTarget_literal_matchesRepoNameUnderAnyOwner() {
        svc.save(targetGrant("alice", "github", MatchTarget.NAME, "repo", MatchType.LITERAL));
        assertTrue(svc.isAllowedToPush("alice", "github", "/a/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/b/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/a/repo2"));
    }

    // ---- regex matching ----

    @Test
    void regexGrant_matchesPattern() {
        svc.save(grant(
                "alice", "github", "/coopernetes/test-repo-.*", MatchType.REGEX, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertTrue(svc.isAllowedToPush("alice", "github", "/coopernetes/test-repo-codeberg"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/coopernetes/test-repo-gitlab"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/coopernetes/test-repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/other/test-repo-codeberg"));
    }

    @Test
    void regexGrant_invalidPattern_treatedAsNoMatch() {
        svc.save(grant("alice", "github", "[invalid", MatchType.REGEX, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    @Test
    void regexGrant_patternCompiledOnce_multipleEvaluations() {
        svc.save(grant("alice", "github", "/org/repo-.*", MatchType.REGEX, RepoPermission.Grant.PUSH_AND_REVIEW));
        for (int i = 0; i < 10; i++) {
            assertTrue(svc.isAllowedToPush("alice", "github", "/org/repo-" + i));
            assertFalse(svc.isAllowedToPush("alice", "github", "/org/other"));
        }
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void regexGrant_catastrophicBacktracking_timesOutAsNoMatch() {
        // Composed greedy loops backtrack polynomially: this pattern against 2000 non-matching chars
        // runs well past the 5s test timeout unguarded (verified on the current JDK). Textbook
        // exponential patterns like (a+)+$ no longer work as fixtures — the JDK 9+ loop memoization
        // neutralizes them — but multi-loop composition is not memoized, so admin-authored patterns
        // can still hang an unguarded matcher. The deadline guard must abort and deny instead.
        // SEPARATE_THREAD so a guard regression fails this test rather than hanging the suite: an
        // interrupt (SAME_THREAD's mechanism) would never stop a regex match.
        svc.save(grant(
                "alice", "github", "a*a*a*a*a*a*a*a*a*a*$", MatchType.REGEX, RepoPermission.Grant.PUSH_AND_REVIEW));
        String value = "a".repeat(2000) + "!";
        assertFalse(svc.isAllowedToPush("alice", "github", value));
    }

    @Test
    void regexGrant_overlongPattern_refusedAsNoMatch() {
        String pattern = "/org/" + "x".repeat(2000);
        svc.save(grant("alice", "github", pattern, MatchType.REGEX, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertFalse(svc.isAllowedToPush("alice", "github", "/org/repo"));
    }

    // ---- seedFromConfig ----

    @Test
    void seedFromConfig_replacesConfigRows_keepsDbRows() {
        RepoPermission dbRow = RepoPermission.builder()
                .username("bob")
                .provider("github")
                .value("/owner/repo")
                .grant(RepoPermission.Grant.PUSH_AND_REVIEW)
                .source(RepoPermission.Source.DB)
                .build();
        svc.save(dbRow);

        RepoPermission configRow = RepoPermission.builder()
                .username("alice")
                .provider("github")
                .value("/owner/repo")
                .grant(RepoPermission.Grant.PUSH_AND_REVIEW)
                .source(RepoPermission.Source.CONFIG)
                .build();
        svc.seedFromConfig(List.of(configRow));

        assertTrue(svc.isAllowedToPush("bob", "github", "/owner/repo"));
        assertTrue(svc.isAllowedToPush("alice", "github", "/owner/repo"));

        svc.seedFromConfig(List.of());
        assertTrue(svc.isAllowedToPush("bob", "github", "/owner/repo"));
        assertFalse(svc.isAllowedToPush("alice", "github", "/owner/repo"));
    }

    // ---- conflict detection ----

    @Test
    void findConflict_exactDuplicatePath_sameOps_detected() {
        svc.save(grant("alice", "github", "/acme/repo"));
        RepoPermission incoming = grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH);
        assertTrue(svc.findConflict(incoming).isPresent());
    }

    @Test
    void findConflict_pushVsPushAndReview_detected() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH));
        RepoPermission incoming =
                grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH_AND_REVIEW);
        assertTrue(svc.findConflict(incoming).isPresent());
    }

    @Test
    void findConflict_pushAndReviewVsSelfCertify_noConflict() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH_AND_REVIEW));
        RepoPermission incoming =
                grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.SELF_CERTIFY);
        assertTrue(svc.findConflict(incoming).isEmpty());
    }

    @Test
    void findConflict_selfCertifyVsSelfCertify_detected() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.SELF_CERTIFY));
        RepoPermission incoming =
                grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.SELF_CERTIFY);
        assertTrue(svc.findConflict(incoming).isPresent());
    }

    @Test
    void findConflict_pushAndReviewVsApiWrite_noConflict() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH_AND_REVIEW));
        RepoPermission incoming =
                grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PROPOSE);
        assertTrue(svc.findConflict(incoming).isEmpty());
    }

    @Test
    void findConflict_selfCertifyVsApiWrite_noConflict() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.SELF_CERTIFY));
        RepoPermission incoming =
                grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PROPOSE);
        assertTrue(svc.findConflict(incoming).isEmpty());
    }

    @Test
    void findConflict_apiWriteVsApiWrite_detected() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PROPOSE));
        RepoPermission incoming =
                grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PROPOSE);
        assertTrue(svc.findConflict(incoming).isPresent());
    }

    @Test
    void findConflict_pushVsReview_noConflict() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH));
        RepoPermission incoming =
                grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.REVIEW);
        assertTrue(svc.findConflict(incoming).isEmpty());
    }

    @Test
    void findConflict_differentUser_noConflict() {
        svc.save(grant("alice", "github", "/acme/repo"));
        RepoPermission incoming = grant("bob", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH);
        assertTrue(svc.findConflict(incoming).isEmpty());
    }

    @Test
    void findConflict_differentProvider_noConflict() {
        svc.save(grant("alice", "github", "/acme/repo"));
        RepoPermission incoming = grant("alice", "gitlab", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH);
        assertTrue(svc.findConflict(incoming).isEmpty());
    }

    @Test
    void findConflict_literalMatchedByExistingGlob_detected() {
        svc.save(grant("alice", "github", "/acme/**", MatchType.GLOB, RepoPermission.Grant.PUSH));
        RepoPermission incoming = grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH);
        assertTrue(svc.findConflict(incoming).isPresent());
    }

    @Test
    void findConflict_incomingGlobMatchesExistingLiteral_detected() {
        svc.save(grant("alice", "github", "/acme/repo"));
        RepoPermission incoming =
                grant("alice", "github", "/acme/**", MatchType.GLOB, RepoPermission.Grant.PUSH_AND_REVIEW);
        assertTrue(svc.findConflict(incoming).isPresent());
    }

    @Test
    void findConflict_globOverlap_subsetDetected() {
        svc.save(grant("alice", "github", "/acme/**", MatchType.GLOB, RepoPermission.Grant.PUSH_AND_REVIEW));
        RepoPermission incoming =
                grant("alice", "github", "/acme/*", MatchType.GLOB, RepoPermission.Grant.PUSH_AND_REVIEW);
        assertTrue(svc.findConflict(incoming).isPresent());
    }

    @Test
    void findConflict_noOverlap_noConflict() {
        svc.save(grant("alice", "github", "/acme/repo-a"));
        RepoPermission incoming =
                grant("alice", "github", "/acme/repo-b", MatchType.LITERAL, RepoPermission.Grant.PUSH);
        assertTrue(svc.findConflict(incoming).isEmpty());
    }

    @Test
    void seedFromConfig_conflictingRows_throwsIllegalStateException() {
        List<RepoPermission> permissions = List.of(
                RepoPermission.builder()
                        .username("alice")
                        .provider("github")
                        .value("/acme/**")
                        .matchType(MatchType.GLOB)
                        .grant(RepoPermission.Grant.PUSH_AND_REVIEW)
                        .source(RepoPermission.Source.CONFIG)
                        .build(),
                RepoPermission.builder()
                        .username("alice")
                        .provider("github")
                        .value("/acme/*")
                        .matchType(MatchType.GLOB)
                        .grant(RepoPermission.Grant.PUSH)
                        .source(RepoPermission.Source.CONFIG)
                        .build());
        assertThrows(IllegalStateException.class, () -> svc.seedFromConfig(permissions));
    }

    // ---- isBypassReviewAllowed ----

    @Test
    void bypassReview_noPermissionsForPath_denied() {
        assertFalse(svc.isBypassReviewAllowed("alice", "github", "/acme/repo"));
    }

    @Test
    void bypassReview_pushAndReview_doesNotImplySelfCertify() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH_AND_REVIEW));
        assertFalse(svc.isBypassReviewAllowed("alice", "github", "/acme/repo"));
    }

    @Test
    void bypassReview_explicitSelfCertify_allowed() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.SELF_CERTIFY));
        assertTrue(svc.isBypassReviewAllowed("alice", "github", "/acme/repo"));
    }

    @Test
    void bypassReview_pathCoveredBySelfCertify_otherUser_denied() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.SELF_CERTIFY));
        assertFalse(svc.isBypassReviewAllowed("bob", "github", "/acme/repo"));
    }

    @Test
    void bypassReview_globSelfCertify_matchedPath_allowed() {
        svc.save(grant("alice", "github", "/acme/*", MatchType.GLOB, RepoPermission.Grant.SELF_CERTIFY));
        assertTrue(svc.isBypassReviewAllowed("alice", "github", "/acme/repo"));
        assertFalse(svc.isBypassReviewAllowed("alice", "github", "/other/repo"));
    }

    // ---- matchesGlob: invalid pattern ----

    @Test
    void glob_invalidPattern_treatedAsNoMatch() {
        svc.save(grant("alice", "github", "{{invalid", MatchType.GLOB, RepoPermission.Grant.PUSH));
        assertFalse(svc.isAllowedToPush("alice", "github", "/acme/repo"));
    }

    // ---- findAll / getGroupStore ----

    @Test
    void findAll_returnsAllSavedPermissions() {
        svc.save(grant("alice", "github", "/acme/a"));
        svc.save(grant("bob", "github", "/acme/b"));
        assertEquals(2, svc.findAll().size());
    }

    @Test
    void getGroupStore_returnsNullWhenNotConfigured() {
        assertNull(svc.getGroupStore());
    }

    @Test
    void seedFromConfig_configConflictsWithExistingDb_throwsIllegalStateException() {
        svc.save(grant("alice", "github", "/acme/repo", MatchType.LITERAL, RepoPermission.Grant.PUSH));
        List<RepoPermission> permissions = List.of(RepoPermission.builder()
                .username("alice")
                .provider("github")
                .value("/acme/**")
                .matchType(MatchType.GLOB)
                .grant(RepoPermission.Grant.PUSH_AND_REVIEW)
                .source(RepoPermission.Source.CONFIG)
                .build());
        assertThrows(IllegalStateException.class, () -> svc.seedFromConfig(permissions));
    }

    @Test
    void seedFromConfig_selfCertifyAlongsidePushAndReview_noConflict() {
        List<RepoPermission> permissions = List.of(
                RepoPermission.builder()
                        .username("alice")
                        .provider("github")
                        .value("/acme/**")
                        .matchType(MatchType.GLOB)
                        .grant(RepoPermission.Grant.PUSH_AND_REVIEW)
                        .source(RepoPermission.Source.CONFIG)
                        .build(),
                RepoPermission.builder()
                        .username("alice")
                        .provider("github")
                        .value("/acme/**")
                        .matchType(MatchType.GLOB)
                        .grant(RepoPermission.Grant.SELF_CERTIFY)
                        .source(RepoPermission.Source.CONFIG)
                        .build());
        assertDoesNotThrow(() -> svc.seedFromConfig(permissions));
    }

    // ---- CRUD delegation ----

    @Test
    void save_findById_delete() {
        RepoPermission p = grant("alice", "github", "/owner/repo");
        svc.save(p);

        assertTrue(svc.findById(p.getId()).isPresent());
        assertEquals("alice", svc.findById(p.getId()).get().getUsername());

        svc.delete(p.getId());
        assertTrue(svc.findById(p.getId()).isEmpty());
    }

    @Test
    void findByUsername_returnsOnlyMatchingRows() {
        svc.save(grant("alice", "github", "/owner/a"));
        svc.save(grant("alice", "github", "/owner/b"));
        svc.save(grant("bob", "github", "/owner/a"));

        List<RepoPermission> alicePerms = svc.findByUsername("alice");
        assertEquals(2, alicePerms.size());
        assertTrue(alicePerms.stream().allMatch(p -> "alice".equals(p.getUsername())));
    }

    @Test
    void findByProvider_returnsOnlyMatchingRows() {
        svc.save(grant("alice", "github", "/owner/a"));
        svc.save(grant("bob", "gitlab", "/owner/b"));

        List<RepoPermission> githubPerms = svc.findByProvider("github");
        assertEquals(1, githubPerms.size());
        assertEquals("alice", githubPerms.get(0).getUsername());
    }
}
