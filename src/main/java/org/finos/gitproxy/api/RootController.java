package org.finos.gitproxy.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

    @GetMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> root() {
        var resource = new ClassPathResource("api/root.json");
        return ResponseEntity.ok(resource);
    }
}
