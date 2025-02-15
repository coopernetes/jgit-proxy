package com.github.coopernetes.jgitproxy.servlet;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

    @GetMapping("/")
    public ResponseEntity<String> root() {
        return ResponseEntity.status(302)
                .header("Location", "https://git-proxy.finos.org")
                .build();
    }

}
