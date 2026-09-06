package com.rbc.fogwall.servlet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScmApiQueryPolicyTest {

    private static final boolean MUTATION = true;
    private static final boolean READ = false;

    @Test
    void noQueryString_isPermitted() {
        assertNull(ScmApiQueryPolicy.refusedParameter(null, READ));
        assertNull(ScmApiQueryPolicy.refusedParameter("", MUTATION));
    }

    @Test
    void readFilteringAndPagination_isPermitted() {
        assertNull(ScmApiQueryPolicy.refusedParameter("state=open&labels=bug&page=2&limit=50", READ));
    }

    @Test
    void listingPullRequestsAsIssues_isPermitted() {
        assertNull(ScmApiQueryPolicy.refusedParameter("type=pulls", READ), "fj lists PRs through the issue endpoint");
    }

    /**
     * The identity split this exists to stop: fogwall authenticates, authorizes and audits the header token while the
     * upstream prefers the query one and acts as somebody else.
     */
    @ParameterizedTest
    @ValueSource(strings = {"access_token", "private_token", "token", "job_token", "sudo"})
    void credentialAndImpersonationParameters_areRefused(String name) {
        assertEquals(name, ScmApiQueryPolicy.refusedParameter(name + "=x", READ));
        assertEquals(name, ScmApiQueryPolicy.refusedParameter(name + "=x", MUTATION));
    }

    @Test
    void credentialHiddenAmongPermittedParameters_isRefused() {
        assertEquals("access_token", ScmApiQueryPolicy.refusedParameter("state=open&access_token=x&page=1", READ));
    }

    @Test
    void percentEncodedParameterName_isRefused() {
        assertEquals("token", ScmApiQueryPolicy.refusedParameter("%74oken=x", READ), "decoded before matching");
    }

    @Test
    void uppercasedParameterName_isRefused() {
        assertEquals("sudo", ScmApiQueryPolicy.refusedParameter("SUDO=root", READ));
    }

    /** No create, edit, close or comment in any dialect carries a query parameter; the body holds everything. */
    @Test
    void mutationCarryingAnyParameter_isRefused() {
        assertEquals("state", ScmApiQueryPolicy.refusedParameter("state=closed", MUTATION));
        assertEquals("body", ScmApiQueryPolicy.refusedParameter("body=text", MUTATION));
    }

    /**
     * The reads {@code glab} fires before every create. Refusing any of them stops the command before the mutation is
     * ever reached, so they are as load-bearing as the write itself: {@code GET /projects/:path} carries the
     * response-shape flags, and every {@code --assignee}/{@code --reviewer} login is resolved to a numeric id first.
     */
    @Test
    void glabsPreMutationReads_arePermitted() {
        assertNull(ScmApiQueryPolicy.refusedParameter("license=true&with_custom_attributes=true", READ));
        assertNull(ScmApiQueryPolicy.refusedParameter("per_page=30&username=someone", READ));
    }

    /** Widening the read set must not widen writes; every captured mutation still carries no query string at all. */
    @Test
    void theSameParametersAreStillRefusedOnAMutation() {
        assertEquals("username", ScmApiQueryPolicy.refusedParameter("username=someone", MUTATION));
        assertEquals("license", ScmApiQueryPolicy.refusedParameter("license=true", MUTATION));
    }

    @Test
    void unknownParameter_isRefusedEvenOnARead() {
        assertEquals("callback", ScmApiQueryPolicy.refusedParameter("callback=evil", READ));
    }

    @Test
    void valuelessParameter_isStillMatchedByName() {
        assertEquals("sudo", ScmApiQueryPolicy.refusedParameter("sudo", READ));
    }
}
