package org.finos.gitproxy.dashboard.controller;

import java.util.Map;
import org.finos.gitproxy.permission.RepoPermission;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.user.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/{username}/permissions")
public class PermissionController {

    @Autowired
    private RepoPermissionService permissionService;

    @Autowired
    private UserStore userStore;

    /** List all permissions for a user. Requires authentication. */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable String username) {
        if (userStore.findByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(permissionService.findByUsername(username));
    }

    /** Add a permission for a user. Requires ROLE_ADMIN. */
    @PostMapping
    public ResponseEntity<?> add(@PathVariable String username, @RequestBody AddPermissionRequest req) {
        if (userStore.findByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (req.provider() == null || req.provider().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        }
        if (req.path() == null || req.path().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "path is required"));
        }

        RepoPermission.PathType pathType;
        try {
            pathType = req.pathType() != null
                    ? RepoPermission.PathType.valueOf(req.pathType().toUpperCase())
                    : RepoPermission.PathType.LITERAL;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid pathType: " + req.pathType()));
        }

        RepoPermission.Operations operations;
        try {
            operations = req.operations() != null
                    ? RepoPermission.Operations.valueOf(req.operations().toUpperCase())
                    : RepoPermission.Operations.ALL;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid operations: " + req.operations()));
        }

        var permission = RepoPermission.builder()
                .username(username)
                .provider(req.provider().trim())
                .path(req.path().trim())
                .pathType(pathType)
                .operations(operations)
                .source(RepoPermission.Source.DB)
                .build();
        permissionService.save(permission);
        return ResponseEntity.status(HttpStatus.CREATED).body(permission);
    }

    /** Delete a permission. Requires ROLE_ADMIN. Config-sourced permissions cannot be deleted. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String username, @PathVariable String id) {
        if (userStore.findByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var existing = permissionService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!username.equals(existing.get().getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Permission does not belong to this user"));
        }
        if (existing.get().getSource() == RepoPermission.Source.CONFIG) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot delete config-defined permissions"));
        }
        permissionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record AddPermissionRequest(String provider, String path, String pathType, String operations) {}
}
