package org.finos.gitproxy.dashboard.controller;

import java.util.HashMap;
import java.util.Map;
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

    /** Returns the currently authenticated user's username and primary email (if known). */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;

        String email = userStore
                .findByUsername(username)
                .flatMap(u -> u.getEmails().stream().findFirst())
                .orElse(null);

        Map<String, Object> result = new HashMap<>();
        result.put("username", username);
        result.put("email", email);
        return result;
    }
}
