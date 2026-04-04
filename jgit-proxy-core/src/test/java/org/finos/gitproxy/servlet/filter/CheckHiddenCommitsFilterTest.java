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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckHiddenCommitsFilterTest {

    @TempDir
    Path tempDir;

    Git git;
    Repository repo;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        repo = git.getRepository();
        repo.getConfig().setBoolean("commit", null, "gpgsign", false);
        repo.getConfig().save();
    }

    private RevCommit createCommit(String msg) throws Exception {
        File f = new File(tempDir.toFile(), UUID.randomUUID() + ".txt");
        Files.writeString(f.toPath(), msg);
        git.add().addFilepattern(".").call();
        return git.commit()
                .setAuthor(new PersonIdent("Dev", "dev@example.com"))
                .setCommitter(new PersonIdent("Dev", "dev@example.com"))
                .setMessage(msg)
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

    private static ServletInputStream emptyInputStream() {
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
        when(req.getInputStream()).thenReturn(emptyInputStream());
        return req;
    }

    @Test
    void lightweightTag_passes() throws Exception {
        // Lightweight tag: newId is a commit SHA — ^{commit} is a no-op, walk finds no new commits.
        RevCommit commit = createCommit("tagged");

        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setBranch("refs/tags/v1.0");
        details.setCommitFrom(ObjectId.zeroId().name());
        details.setCommitTo(commit.name());
        details.setLocalRepository(repo);

        FakeResponse resp = new FakeResponse();
        new CheckHiddenCommitsFilter(new GitHubProvider("/proxy")).doHttpFilter(mockRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Lightweight tag push must not be rejected as hidden commits");
    }

    @Test
    void annotatedTag_passes() throws Exception {
        // Annotated tag: newId is a tag object SHA, not a commit SHA.
        // collectAllNewCommits must dereference via ^{commit} or it throws IncorrectObjectTypeException.
        RevCommit commit = createCommit("tagged");

        ObjectInserter inserter = repo.newObjectInserter();
        TagBuilder tb = new TagBuilder();
        tb.setTag("v2.0");
        tb.setObjectId(commit);
        tb.setTagger(new PersonIdent("Dev", "dev@example.com"));
        tb.setMessage("Release 2.0");
        ObjectId tagObjId = inserter.insert(tb);
        inserter.flush();

        GitRequestDetails details = new GitRequestDetails();
        details.setOperation(HttpOperation.PUSH);
        details.setBranch("refs/tags/v2.0");
        details.setCommitFrom(ObjectId.zeroId().name());
        details.setCommitTo(tagObjId.name());
        details.setLocalRepository(repo);

        FakeResponse resp = new FakeResponse();
        new CheckHiddenCommitsFilter(new GitHubProvider("/proxy")).doHttpFilter(mockRequest(details), resp.mock);

        assertFalse(resp.committed.get(), "Annotated tag push must not throw or reject due to tag object type");
    }
}
