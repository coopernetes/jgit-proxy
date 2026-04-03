package org.finos.gitproxy.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.finos.gitproxy.db.model.PushStep;

/**
 * Shared context for accumulating {@link PushStep} records across pre-receive hooks within a single push. Hooks write
 * steps (diffs, scan results, etc.) to this context, and the persistence hook reads them when saving the final record.
 */
public class PushContext {

    private final List<PushStep> steps = new ArrayList<>();

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
