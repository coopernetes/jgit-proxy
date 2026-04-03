package org.finos.gitproxy.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.finos.gitproxy.db.model.PushCommit;
import org.finos.gitproxy.db.model.PushRecord;
import org.finos.gitproxy.db.model.PushStatus;
import org.finos.gitproxy.git.Commit;
import org.finos.gitproxy.git.Contributor;
import org.finos.gitproxy.git.GitRequestDetails;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.junit.jupiter.api.Test;

class PushRecordMapperTest {

    private static Commit commit(String sha) {
        return Commit.builder()
                .sha(sha)
                .author(Contributor.builder()
                        .name("Alice")
                        .email("alice@example.com")
                        .build())
                .committer(Contributor.builder()
                        .name("Bob")
                        .email("bob@example.com")
                        .build())
                .message("Test commit")
                .date(Instant.now())
                .build();
    }

    @Test
    void mapResult_null_returnsReceived() {
        assertEquals(PushStatus.RECEIVED, PushRecordMapper.mapResult(null));
    }

    @Test
    void mapResult_allValues_mappedCorrectly() {
        assertEquals(PushStatus.PROCESSING, PushRecordMapper.mapResult(GitRequestDetails.GitResult.PENDING));
        assertEquals(PushStatus.APPROVED, PushRecordMapper.mapResult(GitRequestDetails.GitResult.ALLOWED));
        assertEquals(PushStatus.BLOCKED, PushRecordMapper.mapResult(GitRequestDetails.GitResult.BLOCKED));
        assertEquals(PushStatus.REJECTED, PushRecordMapper.mapResult(GitRequestDetails.GitResult.REJECTED));
        assertEquals(PushStatus.RECEIVED, PushRecordMapper.mapResult(GitRequestDetails.GitResult.ACCEPTED));
        assertEquals(PushStatus.ERROR, PushRecordMapper.mapResult(GitRequestDetails.GitResult.ERROR));
    }

    @Test
    void mapCommit_allFields_populated() {
        PushCommit pc = PushRecordMapper.mapCommit("push-1", commit("abc123"));
        assertEquals("push-1", pc.getPushId());
        assertEquals("abc123", pc.getSha());
        assertEquals("Test commit", pc.getMessage());
        assertEquals("Alice", pc.getAuthorName());
        assertEquals("alice@example.com", pc.getAuthorEmail());
        assertEquals("Bob", pc.getCommitterName());
    }

    @Test
    void mapCommit_noAuthorOrCommitter_doesNotThrow() {
        Commit bare = Commit.builder().sha("xyz999").message("bare").build();
        PushCommit pc = PushRecordMapper.mapCommit("push-2", bare);
        assertEquals("xyz999", pc.getSha());
        assertNull(pc.getAuthorName());
        assertNull(pc.getCommitterName());
    }

    @Test
    void fromRequestDetails_basicFields_populated() {
        GitRequestDetails details = new GitRequestDetails();
        details.setBranch("main");
        details.setCommitFrom("aaa000");
        details.setCommitTo("bbb111");
        details.setResult(GitRequestDetails.GitResult.ALLOWED);

        PushRecord record = PushRecordMapper.fromRequestDetails(details);
        assertNotNull(record.getId());
        assertEquals("main", record.getBranch());
        assertEquals("aaa000", record.getCommitFrom());
        assertEquals("bbb111", record.getCommitTo());
        assertEquals(PushStatus.APPROVED, record.getStatus());
    }

    @Test
    void fromRequestDetails_errorResult_setsErrorMessage() {
        GitRequestDetails details = new GitRequestDetails();
        details.setResult(GitRequestDetails.GitResult.ERROR);
        details.setReason("Something broke");

        PushRecord record = PushRecordMapper.fromRequestDetails(details);
        assertEquals("Something broke", record.getErrorMessage());
        assertNull(record.getBlockedMessage());
    }

    @Test
    void fromRequestDetails_blockedResult_setsBlockedMessage() {
        GitRequestDetails details = new GitRequestDetails();
        details.setResult(GitRequestDetails.GitResult.BLOCKED);
        details.setReason("Policy violation");

        PushRecord record = PushRecordMapper.fromRequestDetails(details);
        assertEquals("Policy violation", record.getBlockedMessage());
        assertNull(record.getErrorMessage());
    }

    @Test
    void fromRequestDetails_withRepository_setsFields() {
        GitRequestDetails details = new GitRequestDetails();
        details.setRepository(GitRequestDetails.Repository.builder()
                .owner("myorg")
                .name("myrepo")
                .slug("myorg/myrepo")
                .build());

        PushRecord record = PushRecordMapper.fromRequestDetails(details);
        assertEquals("myorg/myrepo", record.getUrl());
        assertEquals("myorg", record.getProject());
        assertEquals("myrepo", record.getRepoName());
    }

    @Test
    void fromRequestDetails_withProvider_setsUpstreamUrl() {
        GitProxyProvider provider = mock(GitProxyProvider.class);
        when(provider.getName()).thenReturn("github");
        when(provider.getUri()).thenReturn(URI.create("https://github.com/"));

        GitRequestDetails details = new GitRequestDetails();
        details.setRepository(
                GitRequestDetails.Repository.builder().slug("myorg/myrepo").build());
        details.setProvider(provider);

        PushRecord record = PushRecordMapper.fromRequestDetails(details);
        assertEquals("https://github.com/myorg/myrepo", record.getUpstreamUrl());
    }

    @Test
    void fromRequestDetails_withPushedCommits_mapsAllCommits() {
        GitRequestDetails details = new GitRequestDetails();
        details.setPushedCommits(List.of(commit("sha1"), commit("sha2")));

        PushRecord record = PushRecordMapper.fromRequestDetails(details);
        assertEquals(2, record.getCommits().size());
    }
}
