package org.finos.gitproxy.dashboard.controller;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "timestamp", Instant.now().toString());
    }

    @GetMapping("/")
    public Map<String, Object> info() {
        return Map.of(
                "name",
                "jgit-proxy",
                "version",
                "0.0.1-SNAPSHOT",
                "endpoints",
                Map.of("push", "/api/push", "health", "/api/health"));
    }
}
