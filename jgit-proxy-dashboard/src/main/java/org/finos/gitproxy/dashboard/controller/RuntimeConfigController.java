package org.finos.gitproxy.dashboard.controller;

import java.util.List;
import java.util.Map;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves runtime configuration to the SPA. This endpoint is public (no auth required) so the frontend can fetch it
 * before the user logs in — e.g. to learn the API base URL when the frontend is served from a different origin.
 *
 * <p>This is the jgit-proxy equivalent of the {@code runtime-config.json} file written by the Node.js git-proxy
 * {@code docker-entrypoint.sh}. Serving it from Spring means it works in all environments (Gradle run, Docker, bare
 * JAR) without needing to inject files into the static asset directory at container startup.
 */
@RestController
@RequestMapping("/api")
public class RuntimeConfigController {

    @Autowired
    private GitProxyConfig gitProxyConfig;

    @GetMapping("/runtime-config")
    public Map<String, Object> runtimeConfig() {
        List<String> allowedOrigins = gitProxyConfig.getServer().getAllowedOrigins();
        String authProvider = gitProxyConfig.getAuth().getProvider();
        return Map.of("allowedOrigins", allowedOrigins, "authProvider", authProvider);
    }
}
