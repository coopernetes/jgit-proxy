package org.finos.gitproxy.api;

import java.util.Collections;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/src/service/routes/auth.js */
@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    private static final String AUTH_ROOT_JSON = "api/auth.json";

    // TODO: Replace with OpenAPI & Swagger UI
    // https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/src/service/routes/auth.js#L7
    @GetMapping
    public ResponseEntity<Resource> root() {
        var resource = new ClassPathResource(AUTH_ROOT_JSON);
        return ResponseEntity.ok().body(resource);
    }

    // TODO: Replace with spring security
    // https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/src/service/routes/auth.js#L24
    @PostMapping("/login")
    public ResponseEntity<String> login() {
        return ResponseEntity.status(401).body("login failed");
    }

    // TODO: Replace with spring security - pretty sure this can be 100% configuration driven
    // https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/src/service/routes/auth.js#L45
    @GetMapping("/success")
    public ResponseEntity<AfterLoginResponse> success() {
        // stub
        return ResponseEntity.ok(new AfterLoginResponse(true, "user has successfully authenticated"));
    }

    // TODO: Replace with spring security - pretty sure this can be 100% configuration driven
    // https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/src/service/routes/auth.js#L60
    @GetMapping("/failure")
    public ResponseEntity<AfterLoginResponse> failure() {
        return ResponseEntity.status(401).body(new AfterLoginResponse(false, "user failed to authenticate."));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map> logout() {
        // stub
        return ResponseEntity.ok(Map.of("isAuth", true, "user", Collections.emptyMap()));
    }

    // https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/src/service/routes/auth.js#L75
    @GetMapping("/profile")
    public ResponseEntity<UserDetails> profile() {
        return ResponseEntity.ok(new UserDetails("admin", "admin", "admin@example.com", true));
    }

    // https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/src/service/routes/auth.js#L85
    @PostMapping("/gitAccount")
    public ResponseEntity<Void> updateGitAccount() {
        // stub
        return ResponseEntity.ok().build();
    }

    // https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/src/service/routes/auth.js#L114
    @GetMapping("/userLoggedIn")
    public ResponseEntity<UserDetails> userLoggedIn() {
        // stub
        return ResponseEntity.ok(new UserDetails("admin", "admin", "admin@example.com", true));
    }
}

// these are placeholder classes until the implementation is complete
record AfterLoginResponse(boolean success, String message) {}

record UserDetails(String username, String gitAccount, String email, boolean admin) {}
