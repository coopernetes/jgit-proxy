package org.finos.gitproxy.dashboard.controller;

import java.util.List;
import java.util.Map;
import org.finos.gitproxy.user.JdbcUserStore;
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
    private UserStore userStore;

    /**
     * Returns the currently authenticated user's full profile: username, emails (with verified flag), and SCM
     * identities.
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;

        UserEntry user = username != null ? userStore.findByUsername(username).orElse(null) : null;

        List<Map<String, Object>> emails;
        List<Map<String, Object>> scmIdentities;

        if (userStore instanceof JdbcUserStore jdbc && user != null) {
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

        return Map.of(
                "username", username != null ? username : "",
                "emails", emails,
                "scmIdentities", scmIdentities);
    }
}
