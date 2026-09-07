package com.rbc.fogwall.dashboard.controller;

import com.rbc.fogwall.config.ScmOAuthConfig;
import com.rbc.fogwall.jetty.reload.ConfigHolder;
import com.rbc.fogwall.permission.RepoPermissionService;
import com.rbc.fogwall.service.SshScmIdentityEnricher;
import com.rbc.fogwall.ssh.SshKeyUtils;
import com.rbc.fogwall.user.EmailConflictException;
import com.rbc.fogwall.user.LockedByConfigException;
import com.rbc.fogwall.user.LockedEmailException;
import com.rbc.fogwall.user.LockedSshKeyException;
import com.rbc.fogwall.user.ReadOnlyUserStore;
import com.rbc.fogwall.user.ScmIdentityConflictException;
import com.rbc.fogwall.user.SshKeyConflictException;
import com.rbc.fogwall.user.SshKeyEntry;
import com.rbc.fogwall.user.UserStore;
import com.rbc.fogwall.user.VerifiedScmIdentityException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service profile management. Authenticated users can add and remove their own email claims and SCM identity
 * associations.
 *
 * <p>All endpoints return {@code 501 Not Implemented} when the active {@link UserStore} is read-only (e.g.
 * {@code StaticUserStore} used with a memory or mongo database backend).
 */
@Tag(name = "Profile", description = "Self-service profile management for the authenticated user")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProfileController {

    private static final ResponseEntity<Map<String, String>> NOT_MUTABLE = ResponseEntity.status(
                    HttpStatus.NOT_IMPLEMENTED)
            .body(Map.of("error", "Profile mutations are not supported with the current user store backend"));

    private static final ResponseEntity<Map<String, String>> LOCKED_BY_CONFIG = ResponseEntity.status(
                    HttpStatus.FORBIDDEN)
            .body(Map.of("error", "This profile is defined in configuration and cannot be modified at runtime"));

    private static final ResponseEntity<Map<String, String>> STRICT_MODE_MANUAL_ENTRY_DISABLED = ResponseEntity.status(
                    HttpStatus.FORBIDDEN)
            .body(Map.of(
                    "error",
                    "This deployment requires an OAuth-verified SCM identity — manually entering one is disabled."
                            + " Use \"Link via OAuth\" instead."));

    private final ReadOnlyUserStore userStore;

    private final RepoPermissionService permissionService;

    private final ConfigHolder configHolder;

    private final SshScmIdentityEnricher sshEnricher;

    // ---- email claims ----

    @Operation(operationId = "addEmail", summary = "Add an email claim to the current user's profile")
    @PostMapping("/emails")
    public ResponseEntity<?> addEmail(@RequestBody Map<String, String> body) {
        if (!(userStore instanceof UserStore mutable)) return NOT_MUTABLE;
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }
        email = email.strip().toLowerCase();
        String currentUser = currentUsername();
        try {
            mutable.addEmail(currentUser, email);
        } catch (LockedByConfigException e) {
            return LOCKED_BY_CONFIG;
        } catch (EmailConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Email is already registered to another user"));
        }
        return ResponseEntity.ok(Map.of("email", email));
    }

    @Operation(operationId = "removeEmail", summary = "Remove an email claim from the current user's profile")
    @DeleteMapping("/emails/{email}")
    public ResponseEntity<?> removeEmail(@PathVariable String email) {
        if (!(userStore instanceof UserStore mutable)) return NOT_MUTABLE;
        try {
            mutable.removeEmail(currentUsername(), email.toLowerCase());
        } catch (LockedByConfigException e) {
            return LOCKED_BY_CONFIG;
        } catch (LockedEmailException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot remove an email address locked by the identity provider"));
        }
        return ResponseEntity.noContent().build();
    }

    // ---- SCM identity claims ----

    /**
     * In {@code scm-oauth.identity-mode: strict}, a manually-entered identity would never actually be usable for push
     * authorization — {@code CheckUserPushPermissionHook} only honors {@code verified} identities in that mode — so
     * this is disabled server-side (not just hidden in the dashboard) to avoid the confusing dead state of an identity
     * that appears added but can never gate a push. This is deliberately narrower than blocking email claims too:
     * strict identity mode governs which SCM login a push is attributed to, not commit-author-email verification
     * ({@code commit.attribution-policy}), an independent control — see docs/CONFIGURATION.md#scm-oauth.
     */
    @Operation(operationId = "addScmIdentity", summary = "Add an SCM identity to the current user's profile")
    @PostMapping("/identities")
    public ResponseEntity<?> addScmIdentity(@RequestBody Map<String, String> body) {
        if (!(userStore instanceof UserStore mutable)) return NOT_MUTABLE;
        if (configHolder.getScmOAuthConfig().getIdentityMode() == ScmOAuthConfig.IdentityMode.STRICT) {
            return STRICT_MODE_MANUAL_ENTRY_DISABLED;
        }
        String provider = body.get("provider");
        String scmUsername = body.get("username");
        if (provider == null || provider.isBlank() || scmUsername == null || scmUsername.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider and username are required"));
        }
        provider = provider.strip();
        scmUsername = scmUsername.strip();

        String currentUser = currentUsername();
        try {
            mutable.addScmIdentity(currentUser, provider, scmUsername);
        } catch (LockedByConfigException e) {
            return LOCKED_BY_CONFIG;
        } catch (ScmIdentityConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "SCM identity is already claimed by another user"));
        }
        return ResponseEntity.ok(Map.of("provider", provider, "username", scmUsername));
    }

    @Operation(operationId = "removeScmIdentity", summary = "Remove an SCM identity from the current user's profile")
    @DeleteMapping("/identities/{provider}/{scmUsername}")
    public ResponseEntity<?> removeScmIdentity(@PathVariable String provider, @PathVariable String scmUsername) {
        if (!(userStore instanceof UserStore mutable)) return NOT_MUTABLE;
        try {
            mutable.removeScmIdentity(currentUsername(), provider, scmUsername);
        } catch (LockedByConfigException e) {
            return LOCKED_BY_CONFIG;
        } catch (VerifiedScmIdentityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error",
                            "Cannot remove an OAuth-verified SCM identity here — unlink it via /api/scm-oauth/"
                                    + provider + "/unlink instead"));
        }
        return ResponseEntity.noContent().build();
    }

    // ---- SSH key management ----

    @Operation(operationId = "listSshKeys", summary = "List the current user's registered SSH public keys")
    @GetMapping("/ssh-keys")
    public ResponseEntity<?> listSshKeys() {
        if (!(userStore instanceof UserStore mutable)) return NOT_MUTABLE;
        List<SshKeyEntry> keys = mutable.findSshKeys(currentUsername());
        return ResponseEntity.ok(keys.stream()
                .map(k -> Map.of(
                        "id", k.getId(),
                        "fingerprint", k.getFingerprint(),
                        "publicKey", k.getPublicKey(),
                        "label", k.getLabel() != null ? k.getLabel() : "",
                        "createdAt", k.getCreatedAt().toString(),
                        "locked", k.isLocked(),
                        "source", k.getAuthSource() != null ? k.getAuthSource() : "config"))
                .toList());
    }

    @Operation(operationId = "addSshKey", summary = "Register an SSH public key for the current user")
    @PostMapping("/ssh-keys")
    public ResponseEntity<?> addSshKey(@RequestBody Map<String, String> body) {
        if (!(userStore instanceof UserStore mutable)) return NOT_MUTABLE;
        String publicKey = body.get("publicKey");
        String label = body.getOrDefault("label", "");
        if (publicKey == null || publicKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "publicKey is required"));
        }
        String normalised;
        String fingerprint;
        try {
            normalised = SshKeyUtils.normalise(publicKey.strip());
            fingerprint = SshKeyUtils.fingerprint(normalised);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid SSH public key: " + e.getMessage()));
        }
        try {
            SshKeyEntry entry = mutable.addSshKey(currentUsername(), fingerprint, normalised, label);
            evictFingerprintCache(currentUsername());
            return ResponseEntity.ok(Map.of(
                    "id", entry.getId(),
                    "fingerprint", entry.getFingerprint(),
                    "publicKey", entry.getPublicKey(),
                    "label", entry.getLabel() != null ? entry.getLabel() : "",
                    "createdAt", entry.getCreatedAt().toString(),
                    "locked", entry.isLocked(),
                    "source", entry.getAuthSource() != null ? entry.getAuthSource() : "config"));
        } catch (SshKeyConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "SSH key is already registered to another user"));
        }
    }

    @Operation(operationId = "removeSshKey", summary = "Remove a registered SSH public key")
    @DeleteMapping("/ssh-keys/{id}")
    public ResponseEntity<?> removeSshKey(@PathVariable String id) {
        if (!(userStore instanceof UserStore mutable)) return NOT_MUTABLE;
        try {
            mutable.removeSshKey(currentUsername(), id);
        } catch (LockedByConfigException e) {
            return LOCKED_BY_CONFIG;
        } catch (LockedSshKeyException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot remove an SSH key imported from a verified identity provider"));
        }
        evictFingerprintCache(currentUsername());
        return ResponseEntity.noContent().build();
    }

    /**
     * Drops this user's cached provider key listings after their key set changes, so a newly registered key is usable
     * on the next push rather than after the cache TTL — which defaults to seven days.
     *
     * <p>The cache is keyed by {@code (provider, SCM login)} and the change is to the fogwall-side key set, so which
     * provider listing is now stale cannot be narrowed: every linked identity is evicted. Permissive mode only — strict
     * mode resolves from imported keys and never reads this cache.
     */
    private void evictFingerprintCache(String username) {
        if (sshEnricher == null) {
            return;
        }
        userStore.findByUsername(username).ifPresent(user -> {
            if (user.getScmIdentities() == null) {
                return;
            }
            user.getScmIdentities()
                    .forEach(identity -> sshEnricher.evict(identity.getProvider(), identity.getUsername()));
        });
    }

    @Operation(operationId = "getMyPermissions", summary = "Get current user's direct and group-inherited permissions")
    @GetMapping("/permissions")
    public ResponseEntity<?> getPermissions() {
        String username = currentUsername();
        var direct = permissionService.findByUsername(username);
        var groupStore = permissionService.getGroupStore();
        List<PermissionController.UserGroupView> groups = List.of();
        if (groupStore != null) {
            groups = groupStore.findGroupIdsForUser(username).stream()
                    .map(groupStore::findGroupById)
                    .flatMap(java.util.Optional::stream)
                    .map(g -> new PermissionController.UserGroupView(
                            g.getId(),
                            g.getName(),
                            g.getDescription(),
                            g.getSource().name(),
                            groupStore.findRulesForGroup(g.getId())))
                    .toList();
        }
        return ResponseEntity.ok(Map.of("direct", direct, "groups", groups));
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}
