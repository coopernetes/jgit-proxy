package org.finos.gitproxy.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import lombok.Data;
import org.finos.gitproxy.db.model.PushStep;

/**
 * Per-request context shared across all pre/post-receive hooks within a single push. Carries both accumulated
 * {@link PushStep} records (diffs, scan results, etc.) and transient per-request values that must not be stored on the
 * shared cached {@link org.eclipse.jgit.lib.Repository} config.
 *
 * <p>All fields are written once (by {@link StoreAndForwardReceivePackFactory} or early hooks) and read by later hooks.
 * No synchronisation is needed because hooks in a single push execute sequentially on the same thread.
 */
@Data
public class PushContext {

    private final List<PushStep> steps = new ArrayList<>();

    // Per-request credentials — written by StoreAndForwardReceivePackFactory before any hook runs.
    private String pushUser;
    private String pushToken;
    private String repoSlug;

    // Resolved upstream username for Bitbucket pushes — written by BitbucketCredentialRewriteHook.
    private String upstreamUser;

    // Push record ID — written by PushStorePersistenceHook.preReceiveHook().
    private String pushId;

    // Identity resolved by CheckUserPushPermissionHook — written after permission check passes.
    private String resolvedUser;
    private String scmUsername;

    // Validation record ID — written by PushStorePersistenceHook.validationResultHook().
    private String validationRecordId;

    /** Add a step to the context. */
    public void addStep(PushStep step) {
        steps.add(step);
    }

    /** All accumulated steps, in the order they were added. */
    public List<PushStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /** Returns the content of the first step matching {@code stepName}, if present. */
    public Optional<String> getStepContent(String stepName) {
        return steps.stream()
                .filter(s -> stepName.equals(s.getStepName()))
                .findFirst()
                .map(PushStep::getContent);
    }
}
