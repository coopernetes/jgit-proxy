package org.finos.gitproxy.dashboard.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.finos.gitproxy.permission.RepoPermissionService;
import org.finos.gitproxy.user.ReadOnlyUserStore;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    @Autowired
    private ReadOnlyUserStore userStore;

    @Autowired(required = false)
    private RepoPermissionService repoPermissionService;

    /**
     * Returns the currently authenticated user's full profile: username, emails (with verified flag), SCM identities,
     * authorities, and repo permissions.
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;

        UserEntry user = username != null ? userStore.findByUsername(username).orElse(null) : null;

        List<Map<String, Object>> emails;
        List<Map<String, Object>> scmIdentities;

        if (userStore instanceof UserStore jdbc && user != null) {
            emails = jdbc.findEmailsWithVerified(username);
            scmIdentities = jdbc.findScmIdentitiesWithVerified(username);
        } else if (user != null) {
            // StaticUserStore — no verified concept, everything is unverified
            emails = user.getEmails().stream()
                    .<Map<String, Object>>map(
                            e -> Map.of("email", e, "verified", false, "locked", false, "source", "local"))
                    .toList();
            scmIdentities = user.getScmIdentities().stream()
                    .<Map<String, Object>>map(
                            id -> Map.of("provider", id.getProvider(), "username", id.getUsername(), "verified", false))
                    .toList();
        } else {
            emails = List.of();
            scmIdentities = List.of();
        }

        List<String> authorities = new ArrayList<>(
                auth != null
                        ? auth.getAuthorities().stream()
                                .map(a -> a.getAuthority())
                                .toList()
                        : List.of());

        var permissions = username != null && repoPermissionService != null
                ? repoPermissionService.findByUsername(username)
                : List.of();

        return Map.of(
                "username", username != null ? username : "",
                "emails", emails,
                "scmIdentities", scmIdentities,
                "authorities", authorities,
                "permissions", permissions);
    }
}
