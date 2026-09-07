package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;

import com.rbc.fogwall.db.model.ScmApiActionStatus;
import com.rbc.fogwall.scmapi.ScmApiClientType;
import com.rbc.fogwall.servlet.ScmApiErrorResponse;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpHeaders;

/**
 * Classifies the calling client from its {@code User-Agent} and records it on the request context, optionally refusing
 * client types this deployment doesn't intend to serve.
 *
 * <p><b>Defence in depth, not a security boundary.</b> {@code User-Agent} is chosen by the caller, so this filter is
 * built to be strictly <i>subtractive</i>: it can only turn an otherwise-permitted request into a denial, never the
 * other way around. Nothing downstream branches on the classification — the allowlist and the permission engine make
 * the same decision whatever the header says. Forging a CLI's {@code User-Agent} therefore buys an attacker exactly the
 * enforcement a deployment with this filter switched off already has, which is the point: the security floor is
 * unchanged, and this only raises the ceiling for operators who want browsers and stray scripts turned away.
 *
 * <p>Its other job is the durable one: recording the CLI and its version in the audit trail. Every one of the four CLIs
 * advertises its version ({@code GitHub CLI 2.98.0}, {@code glab/v1.116.0}, {@code tea/0.15.1},
 * {@code forgejo-cli/0.6.0}), which is the anchor for noticing that a CLI upgrade has changed its wire format — a
 * mutation that stops matching the allowlist otherwise surfaces only as an unexplained denial.
 *
 * <p>Runs after {@link ScmApiAuthenticateFilter}, so a rejection is attributable to a resolved user in the audit record
 * rather than being anonymous.
 */
@Slf4j
@RequiredArgsConstructor
public class ScmApiUserAgentFilter implements Filter {

    /**
     * When true, only the four recognised SCM CLIs are served and everything else is refused. Default off, because a
     * deployment may legitimately front other automation, and because a CLI release that changes its {@code User-Agent}
     * format would otherwise start failing for reasons unrelated to policy.
     */
    private final boolean requireKnownCli;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        ScmApiClientType clientType = ScmApiClientType.classify(userAgent);

        var context = (ScmApiRequestContext) httpRequest.getAttribute(SCM_API_REQUEST_ATTR);
        if (context != null) {
            context.setUserAgent(userAgent);
            context.setClientType(clientType);
            context.setClientVersion(ScmApiClientType.version(userAgent));
        }

        if (requireKnownCli && !clientType.isKnownCli()) {
            String reason = "Client type " + clientType + " is not permitted";
            log.debug("SCM API request denied: {} (User-Agent: {})", reason, userAgent);
            if (context != null) {
                context.setStatus(ScmApiActionStatus.DENIED);
                context.setReason(reason);
            }
            ScmApiErrorResponse.write(httpResponse, HttpServletResponse.SC_FORBIDDEN, reason);
            return;
        }

        chain.doFilter(request, response);
    }
}
