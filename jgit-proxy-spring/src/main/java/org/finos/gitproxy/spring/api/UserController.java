package org.finos.gitproxy.spring.api;

import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/user", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class UserController {

    @GetMapping()
    public ResponseEntity<Object> getUsers() {
        // TODO: Implement this method
        return ResponseEntity.ok(new ArrayList<>());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> getUser(@PathVariable String id) {
        // TODO: Implement this method
        log.debug("getUser called with id: {}", id);
        return ResponseEntity.ok().build();
    }
}
