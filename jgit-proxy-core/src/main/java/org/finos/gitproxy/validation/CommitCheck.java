package org.finos.gitproxy.validation;

import java.util.List;
import org.finos.gitproxy.git.Commit;

/**
 * A single, mode-independent commit validation rule.
 *
 * <p>Implementations contain only pure validation logic against a {@link List} of {@link Commit} objects. They have no
 * knowledge of HTTP filters, JGit hooks, sideband streams, or any other transport concern — those are the
 * responsibility of the adapter that invokes the check.
 *
 * <p>This separation means each rule is written once and reused by both the transparent-proxy filter chain and the
 * store-and-forward pre-receive hook chain. Future transport modes (SSH, etc.) plug into the same implementations.
 *
 * <h2>Adding a new commit-level validation rule</h2>
 *
 * <ol>
 *   <li>Implement this interface.
 *   <li>Wire the implementation into {@code StoreAndForwardReceivePackFactory} (hook chain) and
 *       {@code GitProxyServletRegistrar} (filter chain).
 *   <li>Add the step name to {@code STEP_DISPLAY_NAMES} in {@code index.html}.
 * </ol>
 *
 * @see DiffCheck for diff-level validation rules (e.g. secret scanning)
 */
public interface CommitCheck {

    /**
     * Validate the supplied commits.
     *
     * @param commits the commits being pushed; never null, may be empty
     * @return violations found; empty list means the check passed
     */
    List<Violation> check(List<Commit> commits);
}
