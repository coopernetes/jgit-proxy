package org.finos.gitproxy.dashboard.controller;

import java.util.List;
import java.util.Map;
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

    /** Returns the currently authenticated user's full profile: username, emails, and SCM identities. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;

        UserEntry user = username != null ? userStore.findByUsername(username).orElse(null) : null;
        List<String> emails = user != null ? user.getEmails() : List.of();
        List<Map<String, String>> scmIdentities = user != null
                ? user.getScmIdentities().stream()
                        .map(id -> Map.of("provider", id.getProvider(), "username", id.getUsername()))
                        .toList()
                : List.of();

        return Map.of("username", username != null ? username : "", "emails", emails, "scmIdentities", scmIdentities);
    }
}
