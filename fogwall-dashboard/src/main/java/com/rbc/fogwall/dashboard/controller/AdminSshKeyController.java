package com.rbc.fogwall.dashboard.controller;

import com.rbc.fogwall.dashboard.service.SshKeyRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the SSH key re-import on demand, for every user rather than waiting for the scheduled sweep.
 *
 * <p>The reason to reach for this is a key revoked upstream that has to stop working now: the periodic sweep defaults
 * to weekly, and until it runs fogwall still honours the key.
 *
 * <p>Requires {@code ROLE_ADMIN} (gated by {@code /api/admin/**} in {@code SecurityConfig}).
 */
@Tag(name = "Admin", description = "Administrative operations — requires ROLE_ADMIN")
@Slf4j
@RestController
@RequestMapping("/api/admin/ssh-keys")
@RequiredArgsConstructor
public class AdminSshKeyController {

    private final SshKeyRefreshService sshKeyRefreshService;

    @Operation(
            operationId = "refreshAllSshKeys",
            summary = "Re-import SSH keys for every user with a linked identity",
            description = "Runs the sweep the scheduler would otherwise run. Keys removed upstream are withdrawn;"
                    + " hand-added keys are untouched. A provider that cannot be read leaves its keys in place, so a"
                    + " transient outage never revokes anyone.")
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshAll() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("SSH key refresh triggered by login={}", auth != null ? auth.getName() : "unknown");
        var summary = sshKeyRefreshService.refreshAll();
        return ResponseEntity.ok(Map.of(
                "usersExamined", summary.usersExamined(),
                "usersChanged", summary.usersChanged(),
                "keysAdded", summary.keysAdded(),
                "keysWithdrawn", summary.keysWithdrawn(),
                "providersUnavailable", summary.failures()));
    }
}
