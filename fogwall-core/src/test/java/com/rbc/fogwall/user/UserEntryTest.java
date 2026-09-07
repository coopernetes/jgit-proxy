package com.rbc.fogwall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Authorization code branches on these lists, so a null one would skip a check rather than fail it. Both an unset field
 * and an explicitly-passed null must read as empty.
 */
class UserEntryTest {

    @Test
    void unsetLists_readAsEmpty() {
        UserEntry user = UserEntry.builder().username("alice").build();
        assertNotNull(user.getEmails());
        assertNotNull(user.getScmIdentities());
        assertNotNull(user.getSshKeys());
        assertTrue(user.getEmails().isEmpty());
        assertTrue(user.getScmIdentities().isEmpty());
        assertTrue(user.getSshKeys().isEmpty());
        assertEquals(List.of("USER"), user.getRoles());
    }

    @Test
    void explicitNulls_readAsEmpty() {
        UserEntry user = UserEntry.builder()
                .username("alice")
                .emails(null)
                .scmIdentities(null)
                .sshKeys(null)
                .roles(null)
                .build();
        assertTrue(user.getEmails().isEmpty());
        assertTrue(user.getScmIdentities().isEmpty());
        assertTrue(user.getSshKeys().isEmpty());
        assertEquals(List.of("USER"), user.getRoles());
    }

    @Test
    void suppliedValuesAreKept() {
        UserEntry user = UserEntry.builder()
                .username("alice")
                .emails(List.of("a@example.com"))
                .roles(List.of("ADMIN"))
                .build();
        assertEquals(List.of("a@example.com"), user.getEmails());
        assertEquals(List.of("ADMIN"), user.getRoles());
    }
}
