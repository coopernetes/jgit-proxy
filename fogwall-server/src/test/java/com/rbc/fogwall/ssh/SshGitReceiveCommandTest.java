package com.rbc.fogwall.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.rbc.fogwall.git.ServerReceivePackFactory;
import com.rbc.fogwall.provider.ForgejoProvider;
import com.rbc.fogwall.ssh.SshGitReceiveCommand.RepoRoute;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SshGitReceiveCommandTest {

    private static final URI GITEA_URI = URI.create("ssh://git@localhost:3022");

    private static SshProviderTarget target(URI sshUri, String pathSuffix) {
        // A provider serves SSH via its ssh endpoint (#531). The http uri shares the same host:port so the
        // host-keyed servletPath still matches the SSH push path; sshUri is what the push forwards over.
        URI httpUri = URI.create("http://" + sshUri.getHost() + (sshUri.getPort() > 0 ? ":" + sshUri.getPort() : ""));
        var provider = ForgejoProvider.builder()
                .name("gitea")
                .uri(httpUri)
                .sshUri(sshUri)
                .pathSuffix(pathSuffix)
                .build();
        return new SshProviderTarget(provider, mock(ServerReceivePackFactory.class));
    }

    private static Map<String, SshProviderTarget> singleRoute() {
        SshProviderTarget giteaTarget = target(GITEA_URI, null);
        return Map.of(giteaTarget.provider().servletPath(), giteaTarget);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void resolvesStandardPath() {
        RepoRoute route = SshGitReceiveCommand.resolveRoute("/localhost:3022/test-owner/test-repo.git", singleRoute());
        assertEquals("test-owner", route.owner());
        assertEquals("test-repo", route.repo());
        assertEquals("ssh://git@localhost:3022/test-owner/test-repo.git", route.upstreamUrl());
        assertEquals("/test-owner/test-repo", route.repoSlug());
    }

    @Test
    void acceptsPathWithoutLeadingSlash() {
        RepoRoute route = SshGitReceiveCommand.resolveRoute("localhost:3022/org/repo.git", singleRoute());
        assertEquals("org", route.owner());
        assertEquals("repo", route.repo());
    }

    @Test
    void acceptsPathWithoutDotGitSuffix() {
        RepoRoute route = SshGitReceiveCommand.resolveRoute("/localhost:3022/owner/repo", singleRoute());
        assertEquals("owner", route.owner());
        assertEquals("repo", route.repo());
    }

    @Test
    void nestedGroupPath_ownerIsTheWholeNamespace() {
        RepoRoute route =
                SshGitReceiveCommand.resolveRoute("/localhost:3022/group/subgroup/project.git", singleRoute());
        assertEquals("group/subgroup", route.owner());
        assertEquals("project", route.repo());
        assertEquals("ssh://git@localhost:3022/group/subgroup/project.git", route.upstreamUrl());
        assertEquals("/group/subgroup/project", route.repoSlug());
    }

    @Test
    void traversalInsideNestedOwner_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SshGitReceiveCommand.resolveRoute("/localhost:3022/group/../escaped/project.git", singleRoute()));
    }

    @Test
    void matchesProviderWithNoPort() {
        URI noPortUri = URI.create("ssh://git@github.com");
        SshProviderTarget githubTarget = target(noPortUri, null);
        Map<String, SshProviderTarget> routes = Map.of(githubTarget.provider().servletPath(), githubTarget);

        RepoRoute route = SshGitReceiveCommand.resolveRoute("/github.com/myorg/myrepo.git", routes);
        assertEquals("myorg", route.owner());
        assertEquals("myrepo", route.repo());
        assertEquals("ssh://git@github.com/myorg/myrepo.git", route.upstreamUrl());
    }

    // ── Multi-provider routing ───────────────────────────────────────────────

    @Test
    void routesToCorrectProviderAmongMultiple() {
        SshProviderTarget giteaTarget = target(GITEA_URI, null);
        SshProviderTarget githubTarget = target(URI.create("ssh://git@github.com"), null);
        Map<String, SshProviderTarget> routes = Map.of(
                giteaTarget.provider().servletPath(), giteaTarget,
                githubTarget.provider().servletPath(), githubTarget);

        RepoRoute giteaRoute = SshGitReceiveCommand.resolveRoute("/localhost:3022/owner/repo.git", routes);
        assertEquals(giteaTarget.provider(), giteaRoute.provider());

        RepoRoute githubRoute = SshGitReceiveCommand.resolveRoute("/github.com/owner/repo.git", routes);
        assertEquals(githubTarget.provider(), githubRoute.provider());
    }

    @Test
    void routesByPathSuffixWhenConfigured() {
        SshProviderTarget target = target(GITEA_URI, "/internal-gitea");
        Map<String, SshProviderTarget> routes = Map.of(target.provider().servletPath(), target);

        RepoRoute route = SshGitReceiveCommand.resolveRoute("/internal-gitea/owner/repo.git", routes);
        assertEquals("owner", route.owner());
        assertEquals("repo", route.repo());
        assertEquals(target.provider(), route.provider());
    }

    @Test
    void resolvesCorrectReceivePackFactoryPerProvider() {
        SshProviderTarget giteaTarget = target(GITEA_URI, null);
        SshProviderTarget githubTarget = target(URI.create("ssh://git@github.com"), null);
        Map<String, SshProviderTarget> routes = Map.of(
                giteaTarget.provider().servletPath(), giteaTarget,
                githubTarget.provider().servletPath(), githubTarget);

        RepoRoute githubRoute = SshGitReceiveCommand.resolveRoute("/github.com/owner/repo.git", routes);
        assertEquals(githubTarget.receivePackFactory(), githubRoute.receivePackFactory());
    }

    // ── Path format errors ────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/localhost:3022/only-two-segments", // missing repo
                "/localhost:3022", // only host
                "/justonething", // single segment
                "" // empty
            })
    void rejectsPathWithTooFewSegments(String path) {
        assertThrows(IllegalArgumentException.class, () -> SshGitReceiveCommand.resolveRoute(path, singleRoute()));
    }

    // ── Provider mismatch ─────────────────────────────────────────────────────

    @Test
    void rejectsWrongHost() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SshGitReceiveCommand.resolveRoute("/github.com:443/org/repo.git", singleRoute()));
        assertTrue(ex.getMessage().contains("github.com:443"));
        assertTrue(ex.getMessage().contains("localhost:3022"));
    }

    @Test
    void rejectsWrongPort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SshGitReceiveCommand.resolveRoute("/localhost:9999/owner/repo.git", singleRoute()));
    }

    @Test
    void rejectsRightHostWrongPort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SshGitReceiveCommand.resolveRoute("/localhost/owner/repo.git", singleRoute()));
    }
}
