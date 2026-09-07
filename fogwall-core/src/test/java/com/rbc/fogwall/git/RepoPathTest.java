package com.rbc.fogwall.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepoPathTest {

    @Test
    @DisplayName("A two-segment path splits into owner and name")
    void twoSegments() {
        RepoPath path = RepoPath.parse("/myorg/repo").orElseThrow();
        assertEquals("myorg", path.owner());
        assertEquals("repo", path.name());
        assertEquals("/myorg/repo", path.slug());
    }

    @Test
    @DisplayName("A nested GitLab group path keeps every segment: the owner is the whole namespace")
    void nestedGroupPath() {
        RepoPath path = RepoPath.parse("/group/subgroup/project").orElseThrow();
        assertEquals("group/subgroup", path.owner());
        assertEquals("project", path.name());
        assertEquals("/group/subgroup/project", path.slug());
    }

    @Test
    @DisplayName("Nesting is not capped: deeper namespaces keep every segment too")
    void deeplyNestedGroupPath() {
        RepoPath path = RepoPath.parse("/a/b/c/d/project.git").orElseThrow();
        assertEquals("a/b/c/d", path.owner());
        assertEquals("project", path.name());
        assertEquals("/a/b/c/d/project", path.slug());
    }

    @ParameterizedTest
    @DisplayName("A trailing git service path is stripped before the repository path is read")
    @ValueSource(
            strings = {
                "/group/subgroup/project.git/info/refs",
                "/group/subgroup/project.git/git-upload-pack",
                "/group/subgroup/project.git/git-receive-pack",
                "/group/subgroup/project/info/refs",
                "/group/subgroup/project"
            })
    void serviceSuffixesStripped(String pathInfo) {
        RepoPath path = RepoPath.parse(pathInfo).orElseThrow();
        assertEquals("group/subgroup", path.owner());
        assertEquals("project", path.name());
        assertEquals("/group/subgroup/project", path.slug());
    }

    @Test
    @DisplayName("Only one service suffix is stripped, so a repository named git-receive-pack still parses")
    void repositoryNamedLikeAService() {
        RepoPath path =
                RepoPath.parse("/myorg/git-receive-pack.git/git-receive-pack").orElseThrow();
        assertEquals("myorg", path.owner());
        assertEquals("git-receive-pack", path.name());
    }

    @Test
    @DisplayName("A subgroup project named 'info' is not mistaken for the /info/refs service path")
    void subgroupProjectNamedInfo() {
        RepoPath path = RepoPath.parse("/group/subgroup/info/info/refs").orElseThrow();
        assertEquals("group/subgroup", path.owner());
        assertEquals("info", path.name());
    }

    @Test
    @DisplayName("A leading slash is optional, so an already-normalised slug parses the same way")
    void leadingSlashOptional() {
        assertEquals(RepoPath.parse("/myorg/repo"), RepoPath.parse("myorg/repo"));
    }

    @Test
    @DisplayName("Only a trailing .git is stripped, not one in the middle of a name")
    void dotGitOnlyStrippedAsSuffix() {
        assertEquals(
                "my.gitrepo", RepoPath.parse("/myorg/my.gitrepo").orElseThrow().name());
    }

    @ParameterizedTest
    @DisplayName("A path that does not name a repository yields no reference at all")
    @ValueSource(strings = {"/myorg", "myorg", "/", "//", "/myorg/", "/info/refs", "  "})
    void unparseablePaths(String pathInfo) {
        assertTrue(RepoPath.parse(pathInfo).isEmpty(), "Should not parse: " + pathInfo);
    }

    @Test
    @DisplayName("A null path yields no reference")
    void nullPath() {
        assertEquals(Optional.empty(), RepoPath.parse(null));
    }

    @ParameterizedTest
    @DisplayName("Traversal is rejected in any segment, including one inside a nested owner")
    @ValueSource(
            strings = {
                "/../repo",
                "/myorg/..",
                "/group/../project",
                "/group/subgroup/../project",
                "/group/sub group/project",
                "/group//project"
            })
    void traversalRejectedInEverySegment(String pathInfo) {
        assertTrue(RepoPath.parse(pathInfo).isEmpty(), "Should not parse: " + pathInfo);
    }
}
