package org.finos.gitproxy.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * CSRF token handler for single-page applications using the double-submit cookie pattern.
 *
 * <p>Spring Security 6+ defers CSRF token loading, which means the {@code XSRF-TOKEN} cookie is not written unless
 * something consumes the token during the request. This handler forces eager loading so the cookie is always present
 * for the SPA to read.
 *
 * <p>When resolving a token value, header submissions (SPA sending {@code X-XSRF-TOKEN}) are resolved against the raw
 * cookie value, while form submissions use the XOR-masked value for BREACH protection.
 */
class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        // Eagerly load the token — this causes CookieCsrfTokenRepository to write the cookie on every response,
        // so the SPA always has a fresh token available without a separate round-trip.
        delegate.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        // Header submissions (SPA) carry the raw cookie value — validate against it directly.
        if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
            return super.resolveCsrfTokenValue(request, csrfToken);
        }
        // Form submissions use the XOR-masked value for BREACH protection.
        return delegate.resolveCsrfTokenValue(request, csrfToken);
    }
}
