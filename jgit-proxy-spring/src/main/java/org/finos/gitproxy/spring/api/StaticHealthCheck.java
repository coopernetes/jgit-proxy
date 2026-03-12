package org.finos.gitproxy.spring.api;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO: Deprecate and remove. Health checking is provided by actuator
// for users, use /actuator/health instead
@RestController
public class StaticHealthCheck {

    @GetMapping(value = "/api/v1/healthcheck", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> healthcheck() {
        return Map.of("message", "ok");
    }
}
