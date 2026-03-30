package org.finos.gitproxy.dashboard.controller;

import jakarta.annotation.Resource;
import java.util.List;
import org.finos.gitproxy.config.ProviderConfigurationSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProviderController {

    @Resource(name = "providers")
    private ProviderConfigurationSource providers;

    @GetMapping("/api/providers")
    public List<ProviderInfo> list() {
        return providers.getProviders().stream()
                .map(p -> new ProviderInfo(
                        p.getName(),
                        p.getUri().toString(),
                        p.getUri().getHost(),
                        "/push" + p.servletPath(),
                        "/proxy" + p.servletPath()))
                .toList();
    }

    public record ProviderInfo(String name, String uri, String host, String pushPath, String proxyPath) {}
}
