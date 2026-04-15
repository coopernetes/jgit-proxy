package org.finos.gitproxy.dashboard.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.user.EmailConflictException;
import org.finos.gitproxy.user.LockedByConfigException;
import org.finos.gitproxy.user.ReadOnlyUserStore;
import org.finos.gitproxy.user.ScmIdentityConflictException;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private ReadOnlyUserStore userStore;

    @Autowired
    private PushStore pushStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** List all users with a summary suitable for the admin list view. */
    @GetMapping
    public List<UserSummary> list() {
        return userStore.findAll().stream().map(this::toSummary).toList();
    }

    /** Get full detail for a single user. */
    @GetMapping("/{username}")
    public ResponseEntity<UserDetail> get(@PathVariable String username) {
        return userStore
                .findByUsername(username)
                .map(u -> ResponseEntity.ok(toDetail(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Create a new local user. Requires ROLE_ADMIN. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateUserRequest req) {
        if (!(userStore instanceof UserStore jdbc)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "User creation requires a JDBC user store"));
        }
        if (req.username() == null || req.username().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }
        if (req.password() == null || req.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "password is required"));
        }
        try {
            String encoded = passwordEncoder.encode(req.password());
            String roles = (req.roles() == null || req.roles().isEmpty()) ? "USER" : String.join(",", req.roles());
            jdbc.createUser(req.username(), encoded, roles);
            if (req.email() != null && !req.email().isBlank()) {
                jdbc.addEmail(req.username(), req.email());
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("username", req.username()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /** Delete a user. Requires ROLE_ADMIN. */
    @DeleteMapping("/{username}")
    public ResponseEntity<?> delete(@PathVariable String username) {
        if (!(userStore instanceof UserStore jdbc)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "User deletion requires a JDBC user store"));
        }
        var target = userStore.findByUsername(username);
        if (target.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (target.get().getRoles().contains("ADMIN")) {
            long adminCount = userStore.findAll().stream()
                    .filter(u -> u.getRoles().contains("ADMIN"))
                    .count();
            if (adminCount <= 1) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Cannot delete the last admin user"));
            }
        }
        jdbc.deleteUser(username);
        return ResponseEntity.noContent().build();
    }

    /** Reset a user's password. Requires ROLE_ADMIN. */
    @PostMapping("/{username}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable String username, @RequestBody ResetPasswordRequest req) {
        if (!(userStore instanceof UserStore jdbc)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "Password reset requires a JDBC user store"));
        }
        if (req.password() == null || req.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "password is required"));
        }
        try {
            jdbc.setPassword(username, passwordEncoder.encode(req.password()));
            return ResponseEntity.ok(Map.of("username", username));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Add an email address to a user. Requires ROLE_ADMIN. */
    @PostMapping("/{username}/emails")
    public ResponseEntity<?> addEmail(@PathVariable String username, @RequestBody AddEmailRequest req) {
        if (!(userStore instanceof UserStore mutable)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "Email management requires a mutable user store"));
        }
        if (req.email() == null || req.email().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }
        if (userStore.findByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            mutable.addEmail(username, req.email());
        } catch (LockedByConfigException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (EmailConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("email", req.email()));
    }

    /** Remove an email address from a user. Requires ROLE_ADMIN. */
    @DeleteMapping("/{username}/emails/{email}")
    public ResponseEntity<?> removeEmail(@PathVariable String username, @PathVariable String email) {
        if (!(userStore instanceof UserStore mutable)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "Email management requires a mutable user store"));
        }
        if (userStore.findByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            mutable.removeEmail(username, email);
        } catch (LockedByConfigException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    /** Add an SCM identity to a user. Requires ROLE_ADMIN. */
    @PostMapping("/{username}/identities")
    public ResponseEntity<?> addIdentity(@PathVariable String username, @RequestBody ScmIdentityRequest req) {
        if (!(userStore instanceof UserStore mutable)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "SCM identity management requires a mutable user store"));
        }
        if (req.provider() == null
                || req.provider().isBlank()
                || req.scmUsername() == null
                || req.scmUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider and scmUsername are required"));
        }
        if (userStore.findByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            mutable.addScmIdentity(username, req.provider(), req.scmUsername());
        } catch (LockedByConfigException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (ScmIdentityConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("provider", req.provider(), "scmUsername", req.scmUsername()));
    }

    /** Remove an SCM identity from a user. Requires ROLE_ADMIN. */
    @DeleteMapping("/{username}/identities/{provider}/{scmUsername}")
    public ResponseEntity<?> removeIdentity(
            @PathVariable String username, @PathVariable String provider, @PathVariable String scmUsername) {
        if (!(userStore instanceof UserStore mutable)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "SCM identity management requires a mutable user store"));
        }
        if (userStore.findByUsername(username).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            // Provider IDs are `{type}/{host}`; frontend swaps `/` → `@` to survive URL path routing.
            mutable.removeScmIdentity(username, provider.replace('@', '/'), scmUsername);
        } catch (LockedByConfigException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    private UserSummary toSummary(UserEntry u) {
        String primaryEmail = u.getEmails().isEmpty() ? null : u.getEmails().get(0);
        List<String> scmProviders = u.getScmIdentities().stream()
                .map(id -> id.getProvider())
                .filter(p -> !"proxy".equals(p))
                .distinct()
                .toList();
        Map<String, Long> pushCounts = countPushesByStatus(u.getUsername());
        return new UserSummary(u.getUsername(), primaryEmail, scmProviders, pushCounts);
    }

    private UserDetail toDetail(UserEntry u) {
        List<Map<String, Object>> emails;
        List<Map<String, Object>> scmIdentities;

        if (userStore instanceof UserStore jdbc) {
            emails = jdbc.findEmailsWithVerified(u.getUsername());
            scmIdentities = jdbc.findScmIdentitiesWithVerified(u.getUsername()).stream()
                    .filter(id -> !"proxy".equals(id.get("provider")))
                    .toList();
        } else {
            emails = u.getEmails().stream()
                    .<Map<String, Object>>map(
                            e -> Map.of("email", e, "verified", false, "locked", false, "source", "local"))
                    .toList();
            scmIdentities = u.getScmIdentities().stream()
                    .filter(id -> !"proxy".equals(id.getProvider()))
                    .<Map<String, Object>>map(
                            id -> Map.of("provider", id.getProvider(), "username", id.getUsername(), "verified", false))
                    .toList();
        }

        Map<String, Long> pushCounts = countPushesByStatus(u.getUsername());
        return new UserDetail(u.getUsername(), emails, scmIdentities, pushCounts);
    }

    private Map<String, Long> countPushesByStatus(String username) {
        List<PushRecord> pushes =
                pushStore.find(PushQuery.builder().user(username).limit(10000).build());
        return pushes.stream().collect(Collectors.groupingBy(p -> p.getStatus().name(), Collectors.counting()));
    }

    public record UserSummary(
            String username, String primaryEmail, List<String> scmProviders, Map<String, Long> pushCounts) {}

    public record UserDetail(
            String username,
            List<Map<String, Object>> emails,
            List<Map<String, Object>> scmIdentities,
            Map<String, Long> pushCounts) {}

    public record CreateUserRequest(String username, String password, String email, List<String> roles) {}

    public record ResetPasswordRequest(String password) {}

    public record ScmIdentityRequest(String provider, String scmUsername) {}

    public record AddEmailRequest(String email) {}
}
