package com.rbc.fogwall.servlet;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the request sub-path that a REST allowlist matches against, keeping it <b>URL-encoded</b>.
 *
 * <p>{@link HttpServletRequest#getPathInfo()} is decoded per the servlet spec, which silently corrupts the repository
 * segment for every path-addressed dialect: GitLab's {@code /projects/acme%2Fwidgets/issues} decodes to
 * {@code /projects/acme/widgets/issues}, so a rule anchored on a single {@code ([^/]+)} project segment stops matching
 * and the request is denied — and, worse, a repository name containing an encoded slash could otherwise be read as an
 * extra path segment and shift which repo the authorization decision is made about.
 *
 * <p>{@link HttpServletRequest#getRequestURI()} is the raw form as sent by the client, so the sub-path is taken from
 * there instead. The servlet path prefix is stripped verbatim; the dialect mount points ({@code /api/v4},
 * {@code /api/v1}) contain no escapes, so comparing them against the raw URI is exact.
 */
public final class ScmApiRestPath {

    private ScmApiRestPath() {}

    /** The still-encoded sub-path below the dialect's mount point, e.g. {@code /projects/acme%2Fwidgets/issues}. */
    public static String rawSubPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return "";
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String prefix = contextPath + servletPath;
        return uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
    }
}
