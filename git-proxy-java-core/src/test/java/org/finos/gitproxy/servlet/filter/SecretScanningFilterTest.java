package org.finos.gitproxy.servlet.filter;

import static org.finos.gitproxy.servlet.GitProxyServlet.GIT_REQUEST_ATTR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Repository;
import org.finos.gitproxy.config.SecretScanConfig;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.GitleaksRunner;
import org.finos.gitproxy.git.HttpOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretScanningFilterTest {

    @TempDir
    File tempDir;

    SecretScanConfig enabledConfig;
    GitleaksRunner runner;
    SecretScanningFilter filter;

    @BeforeEach
    void setUp() {
        enabledConfig = SecretScanConfig.builder().enabled(true).build();
        runner = mock(GitleaksRunner.class);
        filter = new SecretScanningFilter(enabledConfig, runner);
    }

    private HttpServletRequest requestWith(GitRequestDetails details) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(details);
        return req;
    }

    private GitRequestDetails pushDetailsWithRepo() {
        Repository repo = mock(Repository.class);
        when(repo.getDirectory()).thenReturn(tempDir);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setCommitTo("abc123");
        details.setCommitFrom("def456");
        details.setLocalRepository(repo);
        return details;
    }

    // ---- early returns ----

    @Test
    void nullDetails_skipped() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GIT_REQUEST_ATTR)).thenReturn(null);

        filter.doHttpFilter(req, mock(HttpServletResponse.class));

        verifyNoInteractions(runner);
    }

    @Test
    void disabledConfig_skipped() throws Exception {
        SecretScanningFilter disabledFilter = new SecretScanningFilter(
                SecretScanConfig.builder().enabled(false).build(), runner);
        GitRequestDetails details = pushDetailsWithRepo();

        disabledFilter.doHttpFilter(requestWith(details), mock(HttpServletResponse.class));

        verifyNoInteractions(runner);
    }

    @Test
    void nullLocalRepository_skipped() throws Exception {
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setCommitTo("abc123");
        // localRepository not set

        filter.doHttpFilter(requestWith(details), mock(HttpServletResponse.class));

        verifyNoInteractions(runner);
    }

    @Test
    void nullCommitTo_skipped() throws Exception {
        Repository repo = mock(Repository.class);
        when(repo.getDirectory()).thenReturn(tempDir);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setLocalRepository(repo);
        // commitTo is null

        filter.doHttpFilter(requestWith(details), mock(HttpServletResponse.class));

        verifyNoInteractions(runner);
    }

    @Test
    void blankCommitTo_skipped() throws Exception {
        Repository repo = mock(Repository.class);
        when(repo.getDirectory()).thenReturn(tempDir);
        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setLocalRepository(repo);
        details.setCommitTo("   ");

        filter.doHttpFilter(requestWith(details), mock(HttpServletResponse.class));

        verifyNoInteractions(runner);
    }

    // ---- scanner results ----

    @Test
    void scannerUnavailable_failOpen() throws Exception {
        GitRequestDetails details = pushDetailsWithRepo();
        when(runner.scanGit(any(), any(), any(), any())).thenReturn(Optional.empty());

        filter.doHttpFilter(requestWith(details), mock(HttpServletResponse.class));

        // fail-open: no steps added when scanner is unavailable
        assertTrue(details.getSteps().isEmpty());
    }

    @Test
    void noFindings_passes() throws Exception {
        GitRequestDetails details = pushDetailsWithRepo();
        when(runner.scanGit(any(), any(), any(), any())).thenReturn(Optional.of(List.of()));

        filter.doHttpFilter(requestWith(details), mock(HttpServletResponse.class));

        assertTrue(details.getSteps().isEmpty());
    }

    @Test
    void withFindings_recordsIssue() throws Exception {
        GitRequestDetails details = pushDetailsWithRepo();
        GitleaksRunner.Finding finding = mock(GitleaksRunner.Finding.class);
        when(finding.toMessage()).thenReturn("leaked-secret in config.yml:42");
        when(runner.scanGit(any(), any(), any(), any())).thenReturn(Optional.of(List.of(finding)));
        HttpServletRequest req = requestWith(details);

        filter.doHttpFilter(req, mock(HttpServletResponse.class));

        assertFalse(details.getSteps().isEmpty(), "Finding must be recorded as a step");
        assertNotNull(details.getSteps().get(0).getErrorMessage());
    }
}
