package org.finos.gitproxy.dashboard.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.model.PushQuery;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.user.JdbcUserStore;
import org.finos.gitproxy.user.UserEntry;
import org.finos.gitproxy.user.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserStore userStore;

    @Autowired
    private PushStore pushStore;

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

        if (userStore instanceof JdbcUserStore jdbc) {
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
}
