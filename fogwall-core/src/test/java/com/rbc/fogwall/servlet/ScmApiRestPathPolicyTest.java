package com.rbc.fogwall.servlet;

import static com.rbc.fogwall.servlet.ScmApiRestPathPolicy.EncodedSeparators.FORGEJO_FILE_PATH;
import static com.rbc.fogwall.servlet.ScmApiRestPathPolicy.EncodedSeparators.GITLAB_PROJECT_SEGMENT;
import static com.rbc.fogwall.servlet.ScmApiRestPathPolicy.EncodedSeparators.REJECTED;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScmApiRestPathPolicyTest {

    @Test
    void acceptsOrdinaryPathsOnBothDialects() {
        assertTrue(ScmApiRestPathPolicy.isForwardable("/projects/acme%2Fwidgets/issues", GITLAB_PROJECT_SEGMENT));
        assertTrue(ScmApiRestPathPolicy.isForwardable("/projects/1234/merge_requests", GITLAB_PROJECT_SEGMENT));
        assertTrue(ScmApiRestPathPolicy.isForwardable("/repos/acme/widgets/issues", REJECTED));
        assertTrue(ScmApiRestPathPolicy.isForwardable("", REJECTED));
    }

    /** GitLab nests groups, so the project segment legitimately carries more than one encoded separator. */
    @Test
    void acceptsNestedGroupsInTheProjectSegment() {
        assertTrue(ScmApiRestPathPolicy.isForwardable(
                "/projects/group%2Fsubgroup%2Fwidgets/issues", GITLAB_PROJECT_SEGMENT));
    }

    @Test
    void confinesTheEncodedSeparatorToTheProjectSegment() {
        // Right shape, wrong segment: anywhere but index 1 has no dialect justification.
        assertFalse(ScmApiRestPathPolicy.isForwardable("/projects/acme/issues%2Fevil", GITLAB_PROJECT_SEGMENT));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/groups/acme%2Fwidgets/issues", GITLAB_PROJECT_SEGMENT));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/acme%2Fwidgets", GITLAB_PROJECT_SEGMENT));
    }

    @Test
    void rejectsEncodedSeparatorsEntirelyForForgejo() {
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/acme%2Fwidgets/issues", REJECTED));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/projects/acme%2Fwidgets/issues", REJECTED));
    }

    @Test
    void rejectsEncodedBackslashAndMixedCase() {
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/acme%5Cwidgets/issues", REJECTED));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/projects/acme/issues%2fevil", GITLAB_PROJECT_SEGMENT));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/projects/acme%5Cwidgets/x", GITLAB_PROJECT_SEGMENT));
    }

    /**
     * A traversal would let the URL the client library finally resolves differ from the one the allowlist matched and
     * the audit record names — so it is refused whatever the dialect, and in encoded form too.
     */
    @Test
    void rejectsTraversalSegments() {
        assertFalse(ScmApiRestPathPolicy.isForwardable("/projects/../../evil", GITLAB_PROJECT_SEGMENT));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/acme/../widgets", REJECTED));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/projects/%2e%2e/evil", GITLAB_PROJECT_SEGMENT));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/%2E%2E/evil", REJECTED));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/./widgets", REJECTED));
    }

    /**
     * {@code fj} reads a pull request template before creating a PR, and Gitea encodes the repository-relative file
     * path into one segment. Refusing that outright made {@code fj pr create} fail with a 400 from fogwall.
     */
    @Test
    void allowsAnEncodedSlashInAForgejoFilePath() {
        assertTrue(ScmApiRestPathPolicy.isForwardable(
                "/repos/acme/widgets/raw/.forgejo%2Fpull_request_template.md", FORGEJO_FILE_PATH));
        assertTrue(
                ScmApiRestPathPolicy.isForwardable("/repos/acme/widgets/contents/docs%2Freadme.md", FORGEJO_FILE_PATH));
        assertTrue(ScmApiRestPathPolicy.isForwardable("/repos/acme/widgets/media/img%2Flogo.png", FORGEJO_FILE_PATH));
    }

    /** The exception is the file path only — never the segments the authorization decision is read from. */
    @Test
    void stillRefusesAnEncodedSlashInForgejoOwnerOrRepo() {
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/acme%2Fwidgets/raw/file.md", FORGEJO_FILE_PATH));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/acme/widgets%2Fx/raw/file.md", FORGEJO_FILE_PATH));
        // Not a blob endpoint, so no file path to carry one.
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/acme/widgets/issues/1%2Fx", FORGEJO_FILE_PATH));
        assertFalse(ScmApiRestPathPolicy.isForwardable("/repos/acme/widgets/raw/a%5Cb.md", FORGEJO_FILE_PATH));
    }

    @Test
    void rejectsNullAndRelativePaths() {
        assertFalse(ScmApiRestPathPolicy.isForwardable(null, REJECTED));
        assertFalse(ScmApiRestPathPolicy.isForwardable("projects/acme", GITLAB_PROJECT_SEGMENT));
    }
}
