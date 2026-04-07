package org.finos.gitproxy.dashboard.controller;

import java.util.Map;
import org.finos.gitproxy.jetty.reload.LiveConfigLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST endpoint for manually triggering a live config reload.
 *
 * <p>{@code POST /api/config/reload} — reloads all hot-reloadable configuration (commit rules, auth settings) from the
 * configured sources (file watch path or git repository). Provider, server, and database changes are not propagated at
 * runtime; they require a restart.
 *
 * <p>This endpoint requires {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigReloadController {

    @Autowired
    private LiveConfigLoader liveConfigLoader;

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reload() {
        String result = liveConfigLoader.reload();
        return ResponseEntity.ok(Map.of("message", result));
    }
}
