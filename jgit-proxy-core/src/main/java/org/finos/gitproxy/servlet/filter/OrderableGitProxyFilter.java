package org.finos.gitproxy.servlet.filter;

/**
 * Extension of {@link GitProxyFilter} for user-configurable filters whose execution order can be changed at runtime.
 *
 * <p>Built-in filters hard-code their {@code ORDER} constant in their constructor; custom filters that need to be
 * positioned relative to those built-ins should implement this interface so the server infrastructure can call
 * {@link #setOrder(int)} after construction.
 *
 * <p>Valid values follow the ranges documented in {@link GitProxyFilter}. The authorization range (0-199) is suitable
 * for filters that gate access before content validation; the content range (200-399) is suitable for filters that
 * inspect commit content; and the extended range (400-499) is reserved for user-defined post-content filters.
 */
public interface OrderableGitProxyFilter extends GitProxyFilter {

    /**
     * Sets the execution order for this filter. Called by the server infrastructure after the filter is constructed,
     * allowing the operator to position it relative to built-in filters without modifying source code.
     *
     * @param order the desired execution order; see {@link GitProxyFilter} for reserved ranges
     */
    void setOrder(int order);
}
