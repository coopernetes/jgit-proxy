package org.finos.gitproxy.dashboard.controller;

import java.util.Map;
import org.finos.gitproxy.jetty.reload.LiveConfigLoader;
import org.finos.gitproxy.jetty.reload.LiveConfigLoader.Section;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST endpoint for manually triggering a live config reload.
 *
 * <p>{@code POST /api/config/reload[?section=<section>]} — reloads hot-reloadable configuration from the configured
 * sources (file watch path or git repository). The optional {@code section} query parameter restricts the reload to a
 * specific section:
 *
 * <ul>
 *   <li>{@code commit} — per-commit validation rules (author email, message)
 *   <li>{@code diff-scan} — push-level diff content block lists
 *   <li>{@code secret-scan} — gitleaks settings (including inline-config)
 *   <li>{@code rules} — URL access control rules
 *   <li>{@code permissions} — user-to-repo permission grants
 *   <li>{@code all} (default) — all of the above
 * </ul>
 *
 * <p>Provider, server, and database changes are not propagated at runtime; they require a restart.
 *
 * <p>This endpoint requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigReloadController {

    @Autowired
    private LiveConfigLoader liveConfigLoader;

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reload(
            @RequestParam(name = "section", defaultValue = "all") String sectionParam) {
        Section section = Section.fromString(sectionParam);
        String result = liveConfigLoader.reload(section);
        return ResponseEntity.ok(Map.of("message", result));
    }
}
