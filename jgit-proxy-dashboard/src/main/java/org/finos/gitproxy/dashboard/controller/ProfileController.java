package org.finos.gitproxy.dashboard.controller;

import java.util.Map;
import org.finos.gitproxy.user.MutableUserStore;
import org.finos.gitproxy.user.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RestController
@RequestMapping("/api/me")
public class ProfileController {

    private static final ResponseEntity<Map<String, String>> NOT_MUTABLE = ResponseEntity.status(
                    HttpStatus.NOT_IMPLEMENTED)
            .body(Map.of("error", "Profile mutations are not supported with the current user store backend"));

    @Autowired
    private UserStore userStore;

    // ---- email claims ----

    @PostMapping("/emails")
    public ResponseEntity<?> addEmail(@RequestBody Map<String, String> body) {
        if (!(userStore instanceof MutableUserStore mutable)) return NOT_MUTABLE;
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }
        email = email.strip().toLowerCase();

        // Prevent claiming an email already registered to another user
        String currentUser = currentUsername();
        var existing = userStore.findByEmail(email);
        if (existing.isPresent() && !existing.get().getUsername().equals(currentUser)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Email is already registered to another user"));
        }
        // Silently skip if already registered to this user (idempotent)
        if (existing.isPresent()) {
            return ResponseEntity.ok(Map.of("email", email));
        }

        mutable.addEmail(currentUser, email);
        return ResponseEntity.ok(Map.of("email", email));
    }

    @DeleteMapping("/emails/{email}")
    public ResponseEntity<?> removeEmail(@PathVariable String email) {
        if (!(userStore instanceof MutableUserStore mutable)) return NOT_MUTABLE;
        mutable.removeEmail(currentUsername(), email.toLowerCase());
        return ResponseEntity.noContent().build();
    }

    // ---- SCM identity claims ----

    @PostMapping("/identities")
    public ResponseEntity<?> addScmIdentity(@RequestBody Map<String, String> body) {
        if (!(userStore instanceof MutableUserStore mutable)) return NOT_MUTABLE;
        String provider = body.get("provider");
        String scmUsername = body.get("username");
        if (provider == null || provider.isBlank() || scmUsername == null || scmUsername.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider and username are required"));
        }
        provider = provider.strip();
        scmUsername = scmUsername.strip();

        // Prevent claiming a handle already registered to another user for the same provider
        String currentUser = currentUsername();
        var existing = userStore.findByScmIdentity(provider, scmUsername);
        if (existing.isPresent() && !existing.get().getUsername().equals(currentUser)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "SCM identity is already registered to another user"));
        }
        if (existing.isPresent()) {
            return ResponseEntity.ok(Map.of("provider", provider, "username", scmUsername));
        }

        mutable.addScmIdentity(currentUser, provider, scmUsername);
        return ResponseEntity.ok(Map.of("provider", provider, "username", scmUsername));
    }

    @DeleteMapping("/identities/{provider}/{scmUsername}")
    public ResponseEntity<?> removeScmIdentity(@PathVariable String provider, @PathVariable String scmUsername) {
        if (!(userStore instanceof MutableUserStore mutable)) return NOT_MUTABLE;
        mutable.removeScmIdentity(currentUsername(), provider, scmUsername);
        return ResponseEntity.noContent().build();
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}
