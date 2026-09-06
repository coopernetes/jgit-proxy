package com.rbc.fogwall.scmapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GitLabTargetProjectTest {

    private static GitLabTargetProject.Result of(String json) {
        return GitLabTargetProject.targetProjectId(json == null ? null : json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsANumericTargetProjectId() {
        var result = of("{\"source_branch\":\"f\",\"target_project_id\":53539888}");
        assertInstanceOf(GitLabTargetProject.Result.Present.class, result);
        assertEquals("53539888", ((GitLabTargetProject.Result.Present) result).projectId());
    }

    /** A same-project merge request names no separate target; the URL is then the right authorization subject. */
    @Test
    void absentWhenTheFieldIsMissingOrNull() {
        assertInstanceOf(GitLabTargetProject.Result.Absent.class, of("{\"title\":\"t\"}"));
        assertInstanceOf(GitLabTargetProject.Result.Absent.class, of("{\"target_project_id\":null}"));
        assertInstanceOf(GitLabTargetProject.Result.Absent.class, of(""));
        assertInstanceOf(GitLabTargetProject.Result.Absent.class, of(null));
    }

    /**
     * Present-but-unreadable must never collapse into {@link GitLabTargetProject.Result.Absent}: the field's presence
     * says the URL is not the target, so treating it as absent would authorize the fork.
     */
    @Test
    void unusableRatherThanAbsentWhenTheValueCannotBeRead() {
        assertInstanceOf(GitLabTargetProject.Result.Unusable.class, of("{\"target_project_id\":\"12\"}"));
        assertInstanceOf(GitLabTargetProject.Result.Unusable.class, of("{\"target_project_id\":1.5}"));
        assertInstanceOf(GitLabTargetProject.Result.Unusable.class, of("{\"target_project_id\":{\"id\":1}}"));
        assertInstanceOf(GitLabTargetProject.Result.Unusable.class, of("{not json"));
    }

    /** A JSON array or scalar body has no field to read and is not a merge-request body fogwall would act on. */
    @Test
    void absentForANonObjectBody() {
        assertInstanceOf(GitLabTargetProject.Result.Absent.class, of("[1,2,3]"));
        assertInstanceOf(GitLabTargetProject.Result.Absent.class, of("\"hello\""));
    }
}
