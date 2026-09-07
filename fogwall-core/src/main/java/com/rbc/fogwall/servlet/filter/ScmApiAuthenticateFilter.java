package com.rbc.fogwall.servlet.filter;

import static com.rbc.fogwall.servlet.ScmApiRequestContext.SCM_API_REQUEST_ATTR;

import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.service.PushIdentityResolver;
import com.rbc.fogwall.service.ResolvedScmIdentity;
import com.rbc.fogwall.servlet.ScmApiErrorResponse;
import com.rbc.fogwall.servlet.ScmApiRequestContext;
import com.rbc.fogwall.servlet.ScmApiTokenExtractor;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the CLI caller's fogwall identity from their {@code Authorization} header token. Reuses the exact mechanism
 * the git-push path already uses ({@link PushIdentityResolver}) — the BYO-token model means the CLI carries a real
 * upstream PAT or-linked OAuth token, never a fogwall-minted credential, so the same token-in/login-out resolution
 * applies.
 *
 * <p>401s on a missing/unresolvable token. Always registered first in the SCM API filter chain, immediately inside
 * {@link ScmApiAuditFilter}.
 */
@Slf4j
@RequiredArgsConstructor
public class ScmApiAuthenticateFilter implements Filter {

    private final FogwallProvider provider;
    private final PushIdentityResolver pushIdentityResolver;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String token = ScmApiTokenExtractor.extractToken(httpRequest);
        if (token == null || pushIdentityResolver == null) {
            unauthorized(httpResponse, "Missing or unsupported Authorization or PRIVATE-TOKEN header");
            return;
        }

        Optional<ResolvedScmIdentity> identity = pushIdentityResolver.resolveIdentity(provider, "", token);
        if (identity.isEmpty()) {
            unauthorized(httpResponse, "Token did not resolve to a known fogwall identity");
            return;
        }

        var context = new ScmApiRequestContext();
        context.setProvider(provider.getProviderId());
        context.setResolvedUser(identity.get().user().getUsername());
        context.setScmLogin(identity.get().scmLogin());
        httpRequest.setAttribute(SCM_API_REQUEST_ATTR, context);

        chain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response, String reason) throws IOException {
        log.debug("SCM API proxy request rejected: {}", reason);
        ScmApiErrorResponse.write(response, HttpServletResponse.SC_UNAUTHORIZED, reason);
    }
}
