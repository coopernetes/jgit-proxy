package org.finos.gitproxy.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ServiceTest {

    // --- DummyRepositoryService ---

    @Test
    void dummyRepo_isRepositoryAuthorized_alwaysTrue() {
        var svc = new DummyRepositoryService();
        assertTrue(svc.isRepositoryAuthorized("https://github.com/org/repo"));
        assertTrue(svc.isRepositoryAuthorized("https://gitlab.com/org/repo"));
        assertTrue(svc.isRepositoryAuthorized("anything"));
    }

    @Test
    void dummyRepo_repositoryExists_alwaysTrue() {
        var svc = new DummyRepositoryService();
        assertTrue(svc.repositoryExists("https://github.com/org/repo"));
        assertTrue(svc.repositoryExists(""));
        assertTrue(svc.repositoryExists(null));
    }

    // --- DummyUserAuthorizationService ---

    @Test
    void dummyUser_isUserAuthorizedToPush_alwaysTrue() {
        var svc = new DummyUserAuthorizationService();
        assertTrue(svc.isUserAuthorizedToPush("user@example.com", "https://github.com/org/repo"));
        assertTrue(svc.isUserAuthorizedToPush(null, null));
    }

    @Test
    void dummyUser_userExists_alwaysTrue() {
        var svc = new DummyUserAuthorizationService();
        assertTrue(svc.userExists("user@example.com"));
        assertTrue(svc.userExists(null));
    }

    @Test
    void dummyUser_getUsernameByEmail_extractsLocalPart() {
        var svc = new DummyUserAuthorizationService();
        assertEquals("tom", svc.getUsernameByEmail("tom@example.com"));
        assertEquals("john.doe", svc.getUsernameByEmail("john.doe@company.org"));
    }

    @Test
    void dummyUser_getUsernameByEmail_noAtSign_returnsInput() {
        var svc = new DummyUserAuthorizationService();
        assertEquals("notanemail", svc.getUsernameByEmail("notanemail"));
    }

    @Test
    void dummyUser_getUsernameByEmail_null_returnsNull() {
        var svc = new DummyUserAuthorizationService();
        assertNull(svc.getUsernameByEmail(null));
    }
}
