package com.rbc.fogwall.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.hc.core5.http.HttpHeaders;

/**
 * Locates the caller's own upstream token on an SCM API proxy request, and names the header that carried it.
 *
 * <p>Two headers are recognised because the CLIs do not agree on one. {@code gh} and {@code tea}/{@code fj} send
 * {@code Authorization}, with {@code Bearer} or {@code token} as the scheme. {@code glab} sends a personal access token
 * in GitLab's own {@code PRIVATE-TOKEN} header and no {@code Authorization} at all (verified against glab 1.116.0), so
 * reading only {@code Authorization} rejects every {@code glab} request before it reaches the allowlist.
 *
 * <p>{@link #authHeaderName} exists so the forwarding servlets can relay the caller's credential header verbatim. The
 * token is read to resolve identity, never rewritten: fogwall's own lookup is a separate request, and re-scheming the
 * forwarded one would make fogwall's answer differ from what the CLI would have got from the upstream directly.
 */
public final class ScmApiTokenExtractor {

    /** GitLab's personal-access-token header. Not a standard header, hence no constant in {@link HttpHeaders}. */
    public static final String PRIVATE_TOKEN = "PRIVATE-TOKEN";

    private static final String[] SCHEMES = {"Bearer ", "bearer ", "token "};

    private ScmApiTokenExtractor() {}

    /** Returns the raw token from whichever recognised header carries it, or {@code null} if none does. */
    public static String extractToken(HttpServletRequest request) {
        String headerName = authHeaderName(request);
        return headerName == null ? null : extractToken(request.getHeader(headerName));
    }

    /**
     * Returns the name of the header carrying the caller's token — {@code Authorization} for {@code gh}/{@code tea},
     * {@code PRIVATE-TOKEN} for {@code glab} — or {@code null} when neither is present.
     */
    public static String authHeaderName(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        if (isPresent(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return HttpHeaders.AUTHORIZATION;
        }
        if (isPresent(request.getHeader(PRIVATE_TOKEN))) {
            return PRIVATE_TOKEN;
        }
        return null;
    }

    /** Returns the raw token, or {@code null} if {@code headerValue} is missing/blank. */
    public static String extractToken(String headerValue) {
        if (!isPresent(headerValue)) {
            return null;
        }
        String trimmed = headerValue.trim();
        for (String scheme : SCHEMES) {
            if (trimmed.startsWith(scheme)) {
                return trimmed.substring(scheme.length()).trim();
            }
        }
        return trimmed;
    }

    private static boolean isPresent(String headerValue) {
        return headerValue != null && !headerValue.isBlank();
    }
}
