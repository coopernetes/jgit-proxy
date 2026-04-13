package org.finos.gitproxy.dashboard.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.ServerConfig;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PushControllerTest {

    @InjectMocks
    PushController controller;

    @Mock
    PushStore pushStore;

    @Mock
    GitProxyConfig gitProxyConfig;

    // Not injected by default — individual tests that need it set it on the controller directly.
    RepoPermissionService repoPermissionService;

    /** Empty approve body — no attestations, mirrors previous Map.of() usage. */
    private static PushController.ApproveBody approveBody() {
        return new PushController.ApproveBody(null, null, null, null);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static PushRecord blockedPush(String id, String pusher) {
        return PushRecord.builder()
                .id(id)
                .status(PushStatus.PENDING)
                .resolvedUser(pusher)
                .provider("github")
                .url("github.com/acme/repo.git")
                .build();
    }

    private static PushRecord approvedPush(String id) {
        return PushRecord.builder().id(id).status(PushStatus.APPROVED).build();
    }

    private void loginAs(String username, boolean admin) {
        loginAs(username, admin, false);
    }

    private void loginAs(String username, boolean admin, boolean selfCertify) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (admin) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if (selfCertify) authorities.add(new SimpleGrantedAuthority("ROLE_SELF_CERTIFY"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    // ── GET /api/push ─────────────────────────────────────────────────────────────

    @Nested
    class List_ {
        @Test
        void noFilters_delegatesToStore() {
            when(pushStore.find(any())).thenReturn(java.util.List.of());
            var result = controller.list(null, null, null, null, null, 50, 0, true);
            assertEquals(0, result.size());
            verify(pushStore).find(argThat(q -> q.getLimit() == 50 && q.getOffset() == 0));
        }

        @Test
        void invalidStatus_throws400() {
            var ex = assertThrows(
                    ResponseStatusException.class,
                    () -> controller.list("NONSENSE", null, null, null, null, 50, 0, true));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        void validStatus_passedToQuery() {
            when(pushStore.find(any())).thenReturn(java.util.List.of());
            controller.list("PENDING", null, null, null, null, 50, 0, true);
            verify(pushStore).find(argThat(q -> q.getStatus() == PushStatus.PENDING));
        }
    }

    // ── GET /api/push/by-ref/{ref} ────────────────────────────────────────────────

    @Nested
    class GetByRef {
        @Test
        void invalidRefFormat_returns400() {
            var resp = controller.getByRef("nounderscore");
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        }

        @Test
        void exactCommitToMatch_returnsRecord() {
            var push = blockedPush("p1", "alice");
            when(pushStore.find(argThat(q -> "abc123".equals(q.getCommitTo())))).thenReturn(List.of(push));

            var resp = controller.getByRef("def456_abc123");

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(push, resp.getBody());
        }

        @Test
        void shortShaFallback_matchesByPrefix() {
            var push = PushRecord.builder()
                    .id("p1")
                    .status(PushStatus.PENDING)
                    .commitTo("abc12345fullsha")
                    .build();
            // First call (exact commitTo) returns empty; second call (fallback scan) returns full list
            when(pushStore.find(any())).thenReturn(List.of()).thenReturn(List.of(push));

            var resp = controller.getByRef("def456_abc123");

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(push, resp.getBody());
        }

        @Test
        void notFound_returns404() {
            when(pushStore.find(any())).thenReturn(List.of());
            var resp = controller.getByRef("def456_abc123");
            assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        }
    }

    // ── GET /api/push/{id} ────────────────────────────────────────────────────────

    @Nested
    class GetById {
        @Test
        void found_returnsRecord() {
            var push = blockedPush("p1", "alice");
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            var resp = controller.getById("p1");
            assertEquals(HttpStatus.OK, resp.getStatusCode());
        }

        @Test
        void notFound_returns404() {
            when(pushStore.findById("missing")).thenReturn(Optional.empty());
            assertEquals(HttpStatus.NOT_FOUND, controller.getById("missing").getStatusCode());
        }

        @Test
        void canCurrentUserSelfCertify_falseWhenNotAuthenticated() {
            var push = blockedPush("p1", "alice");
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            // No SecurityContext authentication set
            var resp = controller.getById("p1");
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(false, resp.getBody().isCanCurrentUserSelfCertify());
        }

        @Test
        void canCurrentUserSelfCertify_falseWhenViewerIsNotPusher() {
            var push = blockedPush("p1", "alice");
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            loginAs("bob", false, true); // role granted, but bob is not the pusher
            var resp = controller.getById("p1");
            assertEquals(false, resp.getBody().isCanCurrentUserSelfCertify());
        }

        @Test
        void canCurrentUserSelfCertify_falseWhenRoleMissing() throws Exception {
            var push = blockedPush("p1", "alice");
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            loginAs("alice", false, false); // pusher, but no ROLE_SELF_CERTIFY
            // Permission service stub intentionally omitted — the role check short-circuits before
            // computeCanCurrentUserSelfCertify ever consults the permission service.
            repoPermissionService = mock(RepoPermissionService.class);
            var field = PushController.class.getDeclaredField("repoPermissionService");
            field.setAccessible(true);
            field.set(controller, repoPermissionService);

            var resp = controller.getById("p1");
            assertEquals(false, resp.getBody().isCanCurrentUserSelfCertify());
        }

        @Test
        void canCurrentUserSelfCertify_falseWhenPermMissing() throws Exception {
            var push = blockedPush("p1", "alice");
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            loginAs("alice", false, true); // pusher with role
            repoPermissionService = mock(RepoPermissionService.class);
            when(repoPermissionService.isBypassReviewAllowed("alice", "github", "github.com/acme/repo.git"))
                    .thenReturn(false);
            var field = PushController.class.getDeclaredField("repoPermissionService");
            field.setAccessible(true);
            field.set(controller, repoPermissionService);

            var resp = controller.getById("p1");
            assertEquals(false, resp.getBody().isCanCurrentUserSelfCertify());
        }

        @Test
        void canCurrentUserSelfCertify_trueWhenRoleAndPermBothGranted() throws Exception {
            var push = blockedPush("p1", "alice");
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            loginAs("alice", false, true); // pusher with role
            repoPermissionService = mock(RepoPermissionService.class);
            when(repoPermissionService.isBypassReviewAllowed("alice", "github", "github.com/acme/repo.git"))
                    .thenReturn(true);
            var field = PushController.class.getDeclaredField("repoPermissionService");
            field.setAccessible(true);
            field.set(controller, repoPermissionService);

            var resp = controller.getById("p1");
            assertEquals(true, resp.getBody().isCanCurrentUserSelfCertify());
        }
    }

    // ── POST /api/push/{id}/authorise ─────────────────────────────────────────────

    @Nested
    class Approve {
        @Test
        void notFound_returns404() {
            when(pushStore.findById("x")).thenReturn(Optional.empty());
            assertEquals(
                    HttpStatus.NOT_FOUND, controller.approve("x", approveBody()).getStatusCode());
        }

        @Test
        void notBlocked_returns400() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(approvedPush("p1")));
            loginAs("reviewer", false);
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    controller.approve("p1", approveBody()).getStatusCode());
        }

        @Test
        void selfApproval_returns403() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            loginAs("alice", false);
            assertEquals(
                    HttpStatus.FORBIDDEN,
                    controller.approve("p1", approveBody()).getStatusCode());
        }

        @Test
        void unresolvedPusher_returns403() {
            var push = PushRecord.builder().id("p1").status(PushStatus.PENDING).build(); // no resolvedUser
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            loginAs("reviewer", false);
            assertEquals(
                    HttpStatus.FORBIDDEN,
                    controller.approve("p1", approveBody()).getStatusCode());
        }

        @Test
        void admin_bypassesIdentityChecks_returns200() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            when(pushStore.approve(eq("p1"), any())).thenReturn(approvedPush("p1"));
            loginAs("alice", true); // same user as pusher — admin bypass

            assertEquals(HttpStatus.OK, controller.approve("p1", approveBody()).getStatusCode());
        }

        @Test
        void admin_selfApproval_flaggedInAttestation() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            when(pushStore.approve(eq("p1"), any())).thenReturn(approvedPush("p1"));
            loginAs("alice", true);

            controller.approve("p1", approveBody());

            verify(pushStore).approve(eq("p1"), argThat(a -> a.isSelfApproval()));
        }

        @Test
        void selfCertify_nonAdmin_notFlaggedAsAdminOverride() throws Exception {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            when(pushStore.approve(eq("p1"), any())).thenReturn(approvedPush("p1"));
            loginAs("alice", false, true); // ROLE_SELF_CERTIFY granted — prerequisite gate

            repoPermissionService = mock(RepoPermissionService.class);
            when(repoPermissionService.isBypassReviewAllowed("alice", "github", "github.com/acme/repo.git"))
                    .thenReturn(true);
            var field = PushController.class.getDeclaredField("repoPermissionService");
            field.setAccessible(true);
            field.set(controller, repoPermissionService);

            controller.approve("p1", approveBody());

            verify(pushStore).approve(eq("p1"), argThat(a -> !a.isSelfApproval()));
        }

        @Test
        void differentUser_returns200() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            when(pushStore.approve(eq("p1"), any())).thenReturn(approvedPush("p1"));
            loginAs("reviewer", false);

            assertEquals(HttpStatus.OK, controller.approve("p1", approveBody()).getStatusCode());
            verify(pushStore).approve(eq("p1"), argThat(a -> !a.isSelfApproval()));
        }

        @Test
        void repoPermissionService_denies_returns403() throws Exception {
            var push = blockedPush("p1", "alice");
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            loginAs("reviewer", false);

            // require-review-permission=true: permission service must allow the reviewer
            var serverConfig = new ServerConfig();
            serverConfig.setRequireReviewPermission(true);
            when(gitProxyConfig.getServer()).thenReturn(serverConfig);

            repoPermissionService = mock(RepoPermissionService.class);
            when(repoPermissionService.isAllowedToReview("reviewer", "github", "github.com/acme/repo.git"))
                    .thenReturn(false);
            var field = PushController.class.getDeclaredField("repoPermissionService");
            field.setAccessible(true);
            field.set(controller, repoPermissionService);

            assertEquals(
                    HttpStatus.FORBIDDEN,
                    controller.approve("p1", approveBody()).getStatusCode());
        }

        @Test
        void repoPermissionService_allows_proceeds() throws Exception {
            var push = blockedPush("p1", "alice");
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            when(pushStore.approve(eq("p1"), any())).thenReturn(approvedPush("p1"));
            loginAs("reviewer", false);

            // require-review-permission=true: permission service grants access
            var serverConfig = new ServerConfig();
            serverConfig.setRequireReviewPermission(true);
            when(gitProxyConfig.getServer()).thenReturn(serverConfig);

            repoPermissionService = mock(RepoPermissionService.class);
            when(repoPermissionService.isAllowedToReview("reviewer", "github", "github.com/acme/repo.git"))
                    .thenReturn(true);
            var field = PushController.class.getDeclaredField("repoPermissionService");
            field.setAccessible(true);
            field.set(controller, repoPermissionService);

            assertEquals(HttpStatus.OK, controller.approve("p1", approveBody()).getStatusCode());
        }

        @Test
        void admin_bypassesRepoPermissionService() throws Exception {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            when(pushStore.approve(eq("p1"), any())).thenReturn(approvedPush("p1"));
            loginAs("admin", true);

            // Wire up a service that would deny — admin should bypass it
            repoPermissionService = mock(RepoPermissionService.class);
            var field = PushController.class.getDeclaredField("repoPermissionService");
            field.setAccessible(true);
            field.set(controller, repoPermissionService);

            assertEquals(HttpStatus.OK, controller.approve("p1", approveBody()).getStatusCode());
            // isAllowedToReview must never have been called
            org.mockito.Mockito.verifyNoInteractions(repoPermissionService);
        }
    }

    // ── POST /api/push/{id}/reject ────────────────────────────────────────────────

    @Nested
    class Reject {
        @Test
        void missingReason_returns400() {
            assertEquals(
                    HttpStatus.BAD_REQUEST, controller.reject("p1", Map.of()).getStatusCode());
        }

        @Test
        void notFound_returns404() {
            when(pushStore.findById("x")).thenReturn(Optional.empty());
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    controller.reject("x", Map.of("reason", "nope")).getStatusCode());
        }

        @Test
        void notBlocked_returns400() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(approvedPush("p1")));
            loginAs("reviewer", false);
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    controller.reject("p1", Map.of("reason", "nope")).getStatusCode());
        }

        @Test
        void selfApproval_returns403() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            loginAs("alice", false);
            assertEquals(
                    HttpStatus.FORBIDDEN,
                    controller.reject("p1", Map.of("reason", "nope")).getStatusCode());
        }

        @Test
        void success_returns200() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            when(pushStore.reject(eq("p1"), any()))
                    .thenReturn(PushRecord.builder()
                            .id("p1")
                            .status(PushStatus.REJECTED)
                            .build());
            loginAs("reviewer", false);

            var resp = controller.reject("p1", Map.of("reason", "bad commit"));
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            verify(pushStore).reject(eq("p1"), argThat(a -> "bad commit".equals(a.getReason())));
        }
    }

    // ── POST /api/push/{id}/cancel ────────────────────────────────────────────────

    @Nested
    class Cancel {
        @Test
        void notFound_returns404() {
            when(pushStore.findById("x")).thenReturn(Optional.empty());
            assertEquals(HttpStatus.NOT_FOUND, controller.cancel("x", null).getStatusCode());
        }

        @Test
        void notBlocked_returns400() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(approvedPush("p1")));
            loginAs("alice", false);
            assertEquals(HttpStatus.BAD_REQUEST, controller.cancel("p1", null).getStatusCode());
        }

        @Test
        void pusher_canCancelOwnPush() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            when(pushStore.cancel(eq("p1"), any()))
                    .thenReturn(PushRecord.builder()
                            .id("p1")
                            .status(PushStatus.CANCELED)
                            .build());
            loginAs("alice", false);

            assertEquals(HttpStatus.OK, controller.cancel("p1", null).getStatusCode());
        }

        @Test
        void otherUser_cannotCancel() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            loginAs("bob", false);
            assertEquals(HttpStatus.FORBIDDEN, controller.cancel("p1", null).getStatusCode());
        }

        @Test
        void unresolvedPusher_nonAdminCannotCancel() {
            var push = PushRecord.builder().id("p1").status(PushStatus.PENDING).build();
            when(pushStore.findById("p1")).thenReturn(Optional.of(push));
            loginAs("alice", false);
            assertEquals(HttpStatus.FORBIDDEN, controller.cancel("p1", null).getStatusCode());
        }

        @Test
        void admin_canCancelAnyPush() {
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "alice")));
            when(pushStore.cancel(eq("p1"), any()))
                    .thenReturn(PushRecord.builder()
                            .id("p1")
                            .status(PushStatus.CANCELED)
                            .build());
            loginAs("admin", true);

            assertEquals(HttpStatus.OK, controller.cancel("p1", null).getStatusCode());
        }

        @Test
        void admin_cancelOwnPush_allowed() {
            // Admin trying to cancel their own push — cancel doesn't use checkReviewerIdentity, so it's allowed
            when(pushStore.findById("p1")).thenReturn(Optional.of(blockedPush("p1", "admin")));
            when(pushStore.cancel(eq("p1"), any()))
                    .thenReturn(PushRecord.builder()
                            .id("p1")
                            .status(PushStatus.CANCELED)
                            .build());
            loginAs("admin", true);

            assertEquals(HttpStatus.OK, controller.cancel("p1", null).getStatusCode());
        }
    }
}
