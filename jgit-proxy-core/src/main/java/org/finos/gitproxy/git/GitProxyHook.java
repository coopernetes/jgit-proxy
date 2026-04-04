package org.finos.gitproxy.git;

import org.eclipse.jgit.transport.PreReceiveHook;

/**
 * A {@link PreReceiveHook} with an associated order value, used to sort hooks in the store-and-forward receive chain.
 *
 * <p>Hooks are executed in ascending order of their {@link #getOrder()} value. The following ranges mirror the filter
 * order scheme and are reserved:
 *
 * <ul>
 *   <li><b>Negative (&lt;= -1):</b> System lifecycle hooks that must run first or last (e.g., persistence hooks).
 *       Custom hooks must not use negative values.
 *   <li><b>0-199 authorization range:</b> Whitelist and user/repo/provider permission checks.
 *   <li><b>200-399 content filtering range:</b> Commit content validation (emails, messages, diffs, signatures,
 *       secrets). Built-in hooks use multiples of 10 within this range to leave room for custom hooks between them.
 *   <li><b>400-499 post-validation range:</b> Reserved for future use (e.g., outbound commit decoration).
 *   <li><b>500+ extended range:</b> Custom bespoke hooks.
 * </ul>
 *
 * <p>Lifecycle hooks ({@code PushStorePersistenceHook}, {@code ApprovalPreReceiveHook}) do not implement this interface
 * and are always pinned at fixed positions in the chain by {@link StoreAndForwardReceivePackFactory}.
 */
public interface GitProxyHook extends PreReceiveHook {

    /** Returns the order value that determines this hook's position in the chain. */
    int getOrder();

    /** Returns a human-readable name for this hook, used in logging and diagnostics. */
    String getName();
}
