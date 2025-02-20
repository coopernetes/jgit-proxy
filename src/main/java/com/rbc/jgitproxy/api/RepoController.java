package com.rbc.jgitproxy.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/v1/repo", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class RepoController {

    @GetMapping()
    public ResponseEntity<Object> getRepos(@RequestParam Map<String, String> queryParams) {
        // TODO: Implement this method
        log.debug("getReposRoot called with queryParams: {}", queryParams);
        return ResponseEntity.ok(new ArrayList<>());
    }

    @GetMapping("/{name}")
    public ResponseEntity<Object> getRepo(@PathVariable String name) {
        // TODO: Implement this method
        log.debug("getRepo called with name: {}", name);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{name}/user/push")
    public ResponseEntity<Object> addUserCanPush(@PathVariable String name, @RequestBody Map<String, String> body) {
        // TODO: Implement this method
        log.debug("addUserCanPush called with name: {}, body: {}", name, body);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{name}/user/authorise")
    public ResponseEntity<Object> addUserCanAuthorise(
            @PathVariable String name, @RequestBody Map<String, String> body) {
        // TODO: Implement this method
        log.debug("addUserCanAuthorise called with name: {}, body: {}", name, body);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{name}/user/authorise/{username}")
    public ResponseEntity<Object> removeUserCanAuthorise(@PathVariable String name, @PathVariable String username) {
        // TODO: Implement this method
        log.debug("removeUserCanAuthorise called with name: {}, username: {}", name, username);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{name}/user/push/{username}")
    public ResponseEntity<Object> removeUserCanPush(@PathVariable String name, @PathVariable String username) {
        // TODO: Implement this method
        log.debug("removeUserCanPush called with name: {}, username: {}", name, username);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{name}/delete")
    public ResponseEntity<Object> deleteRepo(@PathVariable String name) {
        // TODO: Implement this method
        log.debug("deleteRepo called with name: {}", name);
        return ResponseEntity.ok().build();
    }

    @PostMapping
    public ResponseEntity<Object> createRepo(@RequestBody Map<String, String> body) {
        // TODO: Implement this method
        log.debug("createRepo called with body: {}", body);
        return ResponseEntity.ok().build();
    }
}
