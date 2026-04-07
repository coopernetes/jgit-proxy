package org.finos.gitproxy.dashboard.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.permission.RepoPermission;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PermissionControllerTest {

    @InjectMocks
    PermissionController controller;

    @Mock
    RepoPermissionService permissionService;

    @Mock
    UserStore userStore;

    private static final UserEntry ALICE = UserEntry.builder()
            .username("alice")
            .passwordHash("{noop}pw")
            .emails(List.of())
            .scmIdentities(List.of())
            .roles(List.of("USER"))
            .build();

    private static final RepoPermission DB_PERM = RepoPermission.builder()
            .username("alice")
            .provider("github")
            .path("/acme/repo")
            .pathType(RepoPermission.PathType.LITERAL)
            .operations(RepoPermission.Operations.PUSH)
            .source(RepoPermission.Source.DB)
            .build();

    private static final RepoPermission CONFIG_PERM = RepoPermission.builder()
            .username("alice")
            .provider("github")
            .path("/acme/*")
            .pathType(RepoPermission.PathType.GLOB)
            .operations(RepoPermission.Operations.ALL)
            .source(RepoPermission.Source.CONFIG)
            .build();

    // ── GET /api/users/{username}/permissions ────────────────────────────────────

    @Test
    void list_unknownUser_returns404() {
        when(userStore.findByUsername("nobody")).thenReturn(Optional.empty());

        var resp = controller.list("nobody");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void list_knownUser_returnsPermissions() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        when(permissionService.findByUsername("alice")).thenReturn(List.of(DB_PERM));

        var resp = controller.list("alice");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(List.of(DB_PERM), resp.getBody());
    }

    // ── POST /api/users/{username}/permissions ───────────────────────────────────

    @Test
    void add_unknownUser_returns404() {
        when(userStore.findByUsername("nobody")).thenReturn(Optional.empty());

        var resp =
                controller.add("nobody", new PermissionController.AddPermissionRequest("github", "/a/b", null, null));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        verify(permissionService, never()).save(any());
    }

    @Test
    void add_missingProvider_returns400() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));

        var resp = controller.add("alice", new PermissionController.AddPermissionRequest("", "/a/b", null, null));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void add_missingPath_returns400() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));

        var resp = controller.add("alice", new PermissionController.AddPermissionRequest("github", "  ", null, null));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void add_invalidPathType_returns400() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));

        var resp = controller.add(
                "alice", new PermissionController.AddPermissionRequest("github", "/a/b", "WILDCARD", null));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void add_invalidOperations_returns400() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));

        var resp =
                controller.add("alice", new PermissionController.AddPermissionRequest("github", "/a/b", null, "READ"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void add_defaults_literalAndAll() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        var captor = ArgumentCaptor.forClass(RepoPermission.class);

        var resp = controller.add("alice", new PermissionController.AddPermissionRequest("github", "/a/b", null, null));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        verify(permissionService).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("alice", saved.getUsername());
        assertEquals("github", saved.getProvider());
        assertEquals("/a/b", saved.getPath());
        assertEquals(RepoPermission.PathType.LITERAL, saved.getPathType());
        assertEquals(RepoPermission.Operations.ALL, saved.getOperations());
        assertEquals(RepoPermission.Source.DB, saved.getSource());
    }

    @Test
    void add_explicitGlobAndPush_saved() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        var captor = ArgumentCaptor.forClass(RepoPermission.class);

        var resp = controller.add(
                "alice", new PermissionController.AddPermissionRequest("github", "/acme/*", "GLOB", "PUSH"));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        verify(permissionService).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(RepoPermission.PathType.GLOB, saved.getPathType());
        assertEquals(RepoPermission.Operations.PUSH, saved.getOperations());
    }

    // ── DELETE /api/users/{username}/permissions/{id} ────────────────────────────

    @Test
    void delete_unknownUser_returns404() {
        when(userStore.findByUsername("nobody")).thenReturn(Optional.empty());

        var resp = controller.delete("nobody", "some-id");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        verify(permissionService, never()).delete(any());
    }

    @Test
    void delete_unknownPermission_returns404() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        when(permissionService.findById("missing-id")).thenReturn(Optional.empty());

        var resp = controller.delete("alice", "missing-id");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void delete_permissionBelongsToDifferentUser_returns403() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        var bobPerm = RepoPermission.builder()
                .username("bob")
                .provider("github")
                .path("/a/b")
                .source(RepoPermission.Source.DB)
                .build();
        when(permissionService.findById(bobPerm.getId())).thenReturn(Optional.of(bobPerm));

        var resp = controller.delete("alice", bobPerm.getId());

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(permissionService, never()).delete(any());
    }

    @Test
    void delete_configSourced_returns403() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        when(permissionService.findById(CONFIG_PERM.getId())).thenReturn(Optional.of(CONFIG_PERM));

        var resp = controller.delete("alice", CONFIG_PERM.getId());

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(permissionService, never()).delete(any());
    }

    @Test
    void delete_dbSourced_returns204() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(ALICE));
        when(permissionService.findById(DB_PERM.getId())).thenReturn(Optional.of(DB_PERM));

        var resp = controller.delete("alice", DB_PERM.getId());

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(permissionService).delete(DB_PERM.getId());
    }
}
