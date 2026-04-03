package org.finos.gitproxy.validation;

import java.util.List;
import java.util.Optional;

/**
 * A single, mode-independent diff validation rule.
 *
 * <p>Implementations validate a unified diff string produced from the pushed commits. Like {@link CommitCheck}, they
 * have no knowledge of HTTP filters, JGit hooks, or transport concerns.
 *
 * <p>{@link Optional#empty()} signals fail-open (scanner unavailable, configuration error, etc.) — the push is allowed
 * through and a warning is logged. {@code Optional.of(emptyList)} means the diff passed the check.
 *
 * <h2>Adding a new diff-level validation rule</h2>
 *
 * <ol>
 *   <li>Implement this interface.
 *   <li>Wire the implementation into {@code StoreAndForwardReceivePackFactory} and {@code GitProxyServletRegistrar}.
 *   <li>Add the step name to {@code STEP_DISPLAY_NAMES} in {@code index.html}.
 * </ol>
 *
 * @see CommitCheck for commit-level validation rules
 */
public interface DiffCheck {

    /**
     * Validate the supplied unified diff.
     *
     * @param diff the unified diff string; may be blank
     * @return {@code Optional.empty()} to fail-open (unavailable/error), or violations found (empty = passed)
     */
    Optional<List<Violation>> check(String diff);
}
