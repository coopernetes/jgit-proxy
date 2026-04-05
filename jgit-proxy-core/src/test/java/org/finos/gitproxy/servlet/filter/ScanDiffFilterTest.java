package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.finos.gitproxy.config.CommitConfig;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanDiffFilterTest {

    @TempDir
    Path repoDir;

    Repository repo;
    String baseCommit;
    String cleanCommit;
    String blockedCommit;

    @BeforeEach
    void setUp() throws Exception {
        Git git = Git.init().setDirectory(repoDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();

        // Base commit (empty repo needs an initial file)
        baseCommit = commit(git, "init.txt", "initial content").name();

        // A clean commit — no blocked content
        cleanCommit = commit(git, "clean.txt", "perfectly fine addition").name();

        // A commit with a blocked literal
        blockedCommit = commit(git, "secret.txt", "my api_key=supersecret here").name();
    }

    private RevCommit commit(Git git, String filename, String content) throws Exception {
        File f = repoDir.resolve(filename).toFile();
        Files.writeString(f.toPath(), content + "\n");
        git.add().addFilepattern(".").call();
        return git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage("add " + filename)
                .call();
    }

    private static class FakeResponse {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final AtomicBoolean committed = new AtomicBoolean(false);
        final HttpServletResponse mock;

        FakeResponse() throws IOException {
            mock = mock(HttpServletResponse.class);
            when(mock.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override
                public void write(int b) {
                    body.write(b);
                    committed.set(true);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    body.write(b, off, len);
                    committed.set(true);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener l) {}
            });
            when(mock.isCommitted()).thenAnswer(inv -> committed.get());
        }
    }

    private static ServletInputStream emptyStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        return new ServletInputStream() {
            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener l) {}
        };
    }

    private HttpServletRequest mockRequest(GitRequestDetails details) throws IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-git-receive-pack-request");
        when(req.getRequestURI()).thenReturn("/proxy/github.com/owner/repo.git/git-receive-pack");
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        when(req.getInputStream()).thenReturn(emptyStream());
        return req;
    }

    private GitRequestDetails pushDetails(String fromCommit, String toCommit) {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setRepoRef(GitRequestDetails.RepoRef.builder()
                .owner("owner")
                .name("repo")
                .slug("owner/repo")
                .build());
        details.setCommitFrom(fromCommit);
        details.setCommitTo(toCommit);
        details.setLocalRepository(repo);
        return details;
    }

    private ScanDiffFilter filterWithLiteral(String literal) {
        CommitConfig config = CommitConfig.builder()
                .diff(CommitConfig.DiffConfig.builder()
                        .block(CommitConfig.BlockConfig.builder()
                                .literals(List.of(literal))
                                .build())
                        .build())
                .build();
        return new ScanDiffFilter(new GitHubProvider("/proxy"), config);
    }

    private ScanDiffFilter filterNoRules() {
        return new ScanDiffFilter(new GitHubProvider("/proxy"), CommitConfig.defaultConfig());
    }

    // ---- clean diff → PASS step recorded, no issue ----

    @Test
    void cleanDiff_passes() throws Exception {
        GitRequestDetails details = pushDetails(baseCommit, cleanCommit);
        FakeResponse resp = new FakeResponse();

        filterNoRules().doHttpFilter(mockRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "clean push must not commit response");
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    // ---- diff always stored as a step (for dashboard display) ----

    @Test
    void diffAlwaysStoredAsStep() throws Exception {
        GitRequestDetails details = pushDetails(baseCommit, cleanCommit);
        FakeResponse resp = new FakeResponse();

        filterNoRules().doHttpFilter(mockRequest(details), resp.mock);

        boolean hasDiffStep = details.getSteps().stream().anyMatch(s -> "diff".equals(s.getStepName()));
        assertTrue(hasDiffStep, "diff step should always be recorded for dashboard");
    }

    // ---- blocked literal → REJECTED ----

    @Test
    void blockedLiteral_recordsIssue() throws Exception {
        GitRequestDetails details = pushDetails(cleanCommit, blockedCommit);
        FakeResponse resp = new FakeResponse();

        filterWithLiteral("supersecret").doHttpFilter(mockRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "filter must not commit response (ValidationSummaryFilter does that)");
        assertEquals(GitRequestDetails.GitResult.REJECTED, details.getResult());
        boolean hasScanStep = details.getSteps().stream()
                .anyMatch(s -> "scanDiff".equals(s.getStepName()) && s.getStatus() == StepStatus.FAIL);
        assertTrue(hasScanStep, "scan step should record FAIL on violation");
    }

    // ---- null requestDetails → no-op ----

    @Test
    void nullDetails_noOp() throws Exception {
        HttpServletRequest req = mockRequest(null);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);
        FakeResponse resp = new FakeResponse();

        assertDoesNotThrow(() -> filterNoRules().doHttpFilter(req, resp.mock));
        assertFalse(resp.committed.get());
    }

    // ---- null toCommit → skip ----

    @Test
    void nullToCommit_skips() throws Exception {
        GitRequestDetails details = pushDetails(baseCommit, null);
        FakeResponse resp = new FakeResponse();

        filterNoRules().doHttpFilter(mockRequest(details), resp.mock);

        assertFalse(resp.committed.get());
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    // ---- null localRepository → no-op (EnrichPushCommitsFilter didn't run) ----

    @Test
    void nullRepository_skips() throws Exception {
        GitRequestDetails details = pushDetails(baseCommit, cleanCommit);
        details.setLocalRepository(null);
        FakeResponse resp = new FakeResponse();

        filterNoRules().doHttpFilter(mockRequest(details), resp.mock);

        assertFalse(resp.committed.get());
        assertEquals(GitRequestDetails.GitResult.PENDING, details.getResult());
    }

    // ---- diff step content contains the actual diff text ----

    @Test
    void diffStepContainsDiffContent() throws Exception {
        GitRequestDetails details = pushDetails(baseCommit, cleanCommit);
        FakeResponse resp = new FakeResponse();

        filterNoRules().doHttpFilter(mockRequest(details), resp.mock);

        PushStep diffStep = details.getSteps().stream()
                .filter(s -> "diff".equals(s.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("pushDiff step not found"));
        assertNotNull(diffStep.getContent(), "diff step must have content");
        assertFalse(diffStep.getContent().isBlank(), "diff step content must not be blank");
    }
}
