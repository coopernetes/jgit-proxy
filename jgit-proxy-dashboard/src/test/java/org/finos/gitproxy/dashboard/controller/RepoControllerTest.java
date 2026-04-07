package org.finos.gitproxy.dashboard.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.db.FetchStore;
import org.finos.gitproxy.db.FetchStore.RepoFetchSummary;
import org.finos.gitproxy.db.PushStore;
import org.finos.gitproxy.db.RepoRegistry;
import org.finos.gitproxy.db.model.AccessRule;
import org.finos.gitproxy.db.model.PushRecord;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class RepoControllerTest {

    @InjectMocks
    RepoController controller;

    @Mock
    RepoRegistry repoRegistry;

    @Mock
    FetchStore fetchStore;

    @Mock
    PushStore pushStore;

    // ── GET /api/repos/rules ──────────────────────────────────────────────────────

    @Test
    void listRules_delegatesToRegistry() {
        var rule = AccessRule.builder().provider("github").build();
        when(repoRegistry.findAll()).thenReturn(List.of(rule));

        var result = controller.listRules();

        assertEquals(1, result.size());
        assertEquals("github", result.get(0).getProvider());
    }

    // ── GET /api/repos/rules/{id} ─────────────────────────────────────────────────

    @Test
    void getRule_found_returns200() {
        var rule = AccessRule.builder().build();
        when(repoRegistry.findById("r1")).thenReturn(Optional.of(rule));

        assertEquals(HttpStatus.OK, controller.getRule("r1").getStatusCode());
    }

    @Test
    void getRule_notFound_returns404() {
        when(repoRegistry.findById("missing")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getRule("missing").getStatusCode());
    }

    // ── POST /api/repos/rules ─────────────────────────────────────────────────────

    @Test
    void createRule_setsSourceToDb_returns201() {
        var rule = AccessRule.builder()
                .provider("github")
                .source(AccessRule.Source.CONFIG)
                .build();

        var resp = controller.createRule(rule);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals(AccessRule.Source.DB, resp.getBody().getSource());
        verify(repoRegistry).save(rule);
    }

    // ── PUT /api/repos/rules/{id} ─────────────────────────────────────────────────

    @Test
    void updateRule_found_setsIdAndReturns200() {
        var existing = AccessRule.builder().build();
        var update = AccessRule.builder().provider("github").build();
        when(repoRegistry.findById("r1")).thenReturn(Optional.of(existing));

        var resp = controller.updateRule("r1", update);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("r1", update.getId());
        verify(repoRegistry).update(update);
    }

    @Test
    void updateRule_notFound_returns404() {
        when(repoRegistry.findById("missing")).thenReturn(Optional.empty());

        assertEquals(
                HttpStatus.NOT_FOUND,
                controller.updateRule("missing", AccessRule.builder().build()).getStatusCode());
    }

    // ── DELETE /api/repos/rules/{id} ──────────────────────────────────────────────

    @Test
    void deleteRule_found_returns204() {
        when(repoRegistry.findById("r1"))
                .thenReturn(Optional.of(AccessRule.builder().build()));

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteRule("r1").getStatusCode());
        verify(repoRegistry).delete("r1");
    }

    @Test
    void deleteRule_notFound_returns404() {
        when(repoRegistry.findById("missing")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.deleteRule("missing").getStatusCode());
    }

    // ── GET /api/repos/active ─────────────────────────────────────────────────────

    @Nested
    class ActiveRepos {

        @Test
        void empty_returnsEmptyList() {
            when(pushStore.find(any())).thenReturn(List.of());
            when(fetchStore.summarizeByRepo()).thenReturn(List.of());

            assertEquals(List.of(), controller.activeRepos());
        }

        @Test
        void pushRecords_aggregatedByRepo() {
            var push = PushRecord.builder()
                    .upstreamUrl("https://github.com/acme/myrepo.git")
                    .project("acme")
                    .repoName("myrepo")
                    .build();
            when(pushStore.find(any())).thenReturn(List.of(push, push)); // two pushes to same repo
            when(fetchStore.summarizeByRepo()).thenReturn(List.of());

            var result = controller.activeRepos();

            assertEquals(1, result.size());
            assertEquals("github.com", result.get(0).get("provider"));
            assertEquals("acme", result.get(0).get("owner"));
            assertEquals("myrepo", result.get(0).get("repoName"));
            assertEquals(2L, result.get(0).get("pushCount"));
            assertEquals(0L, result.get(0).get("fetchCount"));
        }

        @Test
        void fetchSummaries_mergedWithPushData() {
            var push = PushRecord.builder()
                    .upstreamUrl("https://github.com/acme/myrepo.git")
                    .project("acme")
                    .repoName("myrepo")
                    .build();
            var fetchSummary = new RepoFetchSummary("github.com", "acme", "myrepo", 10L, 2L);
            when(pushStore.find(any())).thenReturn(List.of(push));
            when(fetchStore.summarizeByRepo()).thenReturn(List.of(fetchSummary));

            var result = controller.activeRepos();

            assertEquals(1, result.size());
            assertEquals(1L, result.get(0).get("pushCount"));
            assertEquals(10L, result.get(0).get("fetchCount"));
            assertEquals(2L, result.get(0).get("blockedFetchCount"));
        }

        @Test
        void fetchOnly_repo_appearsInResults() {
            when(pushStore.find(any())).thenReturn(List.of());
            when(fetchStore.summarizeByRepo())
                    .thenReturn(List.of(new RepoFetchSummary("gitlab.com", "org", "repo", 5L, 0L)));

            var result = controller.activeRepos();

            assertEquals(1, result.size());
            assertEquals("gitlab.com", result.get(0).get("provider"));
            assertEquals(0L, result.get(0).get("pushCount"));
            assertEquals(5L, result.get(0).get("fetchCount"));
        }

        @Test
        void sortedByTotalActivityDescending() {
            var busy = PushRecord.builder()
                    .upstreamUrl("https://github.com/acme/busy.git")
                    .project("acme")
                    .repoName("busy")
                    .build();
            var quiet = PushRecord.builder()
                    .upstreamUrl("https://github.com/acme/quiet.git")
                    .project("acme")
                    .repoName("quiet")
                    .build();
            // busy gets 3 pushes + 10 fetches = 13; quiet gets 1 push + 0 fetches = 1
            when(pushStore.find(any())).thenReturn(List.of(busy, busy, busy, quiet));
            when(fetchStore.summarizeByRepo())
                    .thenReturn(List.of(new RepoFetchSummary("github.com", "acme", "busy", 10L, 0L)));

            var result = controller.activeRepos();

            assertEquals(2, result.size());
            assertEquals("busy", result.get(0).get("repoName"));
            assertEquals("quiet", result.get(1).get("repoName"));
        }

        @Test
        void nullUpstreamUrl_usesUnknownProvider() {
            var push = PushRecord.builder()
                    .upstreamUrl(null)
                    .project("acme")
                    .repoName("myrepo")
                    .build();
            when(pushStore.find(any())).thenReturn(List.of(push));
            when(fetchStore.summarizeByRepo()).thenReturn(List.of());

            var result = controller.activeRepos();

            assertEquals("unknown", result.get(0).get("provider"));
        }

        @Test
        void malformedUpstreamUrl_usesUnknownProvider() {
            var push = PushRecord.builder()
                    .upstreamUrl("not a valid url ::::")
                    .project("acme")
                    .repoName("myrepo")
                    .build();
            when(pushStore.find(any())).thenReturn(List.of(push));
            when(fetchStore.summarizeByRepo()).thenReturn(List.of());

            var result = controller.activeRepos();

            assertEquals("unknown", result.get(0).get("provider"));
        }
    }
}
