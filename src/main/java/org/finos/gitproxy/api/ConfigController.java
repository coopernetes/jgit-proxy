package org.finos.gitproxy.api;

import lombok.RequiredArgsConstructor;
import org.finos.gitproxy.config.LegacyJSONConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/config", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ConfigController {

    private final LegacyJSONConfiguration legacyConfig;

    @GetMapping
    public ResponseEntity<Object> getConfig() {
        return ResponseEntity.ok(legacyConfig);
    }

    @GetMapping("/attestation")
    public ResponseEntity<Object> getAttestationConfig() {
        return ResponseEntity.ok(legacyConfig.getAttestationConfig());
    }

    @GetMapping("/urlShortener")
    public ResponseEntity<Object> getURLShortener() {
        return ResponseEntity.ok(legacyConfig.getUrlShortener());
    }

    @GetMapping("/contactEmail")
    public ResponseEntity<Object> getContactEmail() {
        return ResponseEntity.ok(legacyConfig.getContactEmail());
    }
}
