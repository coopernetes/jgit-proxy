package com.rbc.fogwall.scmapi;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.hc.client5.http.fluent.Request;

/**
 * The {@code User-Agent} fogwall presents upstream, which depends on whose request it is.
 *
 * <p>{@link #relay} carries the calling CLI's own value onto a request fogwall is brokering. Without it the provider
 * receives the HTTP client library's default, and rate-limit tiering, abuse heuristics and API-deprecation notices key
 * off this header — a proxied request not carrying the CLI's value is not the request the CLI would have made.
 *
 * <p>{@link #self} names fogwall on a request fogwall originates, such as a node-ID or project-ID lookup. Those are
 * fogwall's own calls, so presenting the caller's CLI would attribute to it a request it never sent.
 *
 * <p>A caller sending no {@code User-Agent} still gets the library default on a relayed request: inventing one would
 * put a claim on the wire the caller never made.
 */
public final class ScmApiUserAgent {

    private static final String USER_AGENT = "User-Agent";

    /** What fogwall calls itself. Unversioned, so it cannot go stale against the build. */
    private static final String FOGWALL = "fogwall";

    private ScmApiUserAgent() {}

    /** The caller's {@code User-Agent}, as the forwarders read it off the incoming request. */
    public static String of(HttpServletRequest request) {
        return request.getHeader(USER_AGENT);
    }

    /** Carries {@code callerUserAgent} onto a brokered request, leaving it alone when the caller sent none. */
    public static void relay(Request upstreamRequest, String callerUserAgent) {
        if (callerUserAgent != null && !callerUserAgent.isBlank()) {
            upstreamRequest.addHeader(USER_AGENT, callerUserAgent);
        }
    }

    /** Identifies fogwall on a request it originates rather than brokers. */
    public static void self(Request request) {
        request.addHeader(USER_AGENT, FOGWALL);
    }
}
