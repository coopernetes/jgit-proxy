package com.rbc.fogwall.dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rbc.fogwall.config.FogwallConfig;
import com.rbc.fogwall.config.OAuthProviderSettings;
import com.rbc.fogwall.config.ProviderConfig;
import com.rbc.fogwall.crypto.TokenCipherProvider;
import com.rbc.fogwall.dashboard.service.ScmSshKeyImporter;
import com.rbc.fogwall.net.FogwallHttpExecutor;
import com.rbc.fogwall.provider.FogwallProvider;
import com.rbc.fogwall.provider.ForgejoProvider;
import com.rbc.fogwall.provider.GitHubProvider;
import com.rbc.fogwall.provider.GitLabProvider;
import com.rbc.fogwall.provider.ProviderRegistry;
import com.rbc.fogwall.user.EmailConflictException;
import com.rbc.fogwall.user.ReadOnlyUserStore;
import com.rbc.fogwall.user.ScmIdentityConflictException;
import com.rbc.fogwall.user.ScmOAuthTokenStore;
import com.rbc.fogwall.user.UserStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Form;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCM OAuth account linking (#40). Not an authentication flow — the user is already logged into the dashboard
 * (local/LDAP/AD/OIDC); this links their proxy account to an upstream SCM identity via OAuth, producing a
 * {@code verified = true} {@code user_scm_identities} row usable by {@code CheckUserPushPermissionHook} in strict
 * identity mode.
 *
 * <p>{@code {providerId}} is the same provider-instance key used in the top-level {@code providers:} config map (not a
 * provider type) — an operator running two GitHub OAuth apps at once (e.g. github.com and a separate {@code *.ghe.com}
 * data-residency tenant) configures them as two {@code providers:} entries, each with its own nested
 * {@code providers.<name>.oauth} block, and links against each independently via its own id.
 *
 * <p>Registered under {@code /api/scm-oauth/**} (not a bare {@code /scm-oauth/**} top-level path) so it falls inside
 * the dashboard's existing {@code /api/**}-scoped Spring Security filter chain and inherits its
 * {@code anyRequest().authenticated()} default — a top-level path outside {@code /api/**} would bypass authentication
 * entirely under this dashboard's single-filter-chain setup.
 *
 * <p>For a GitHub App specifically: its permissions come entirely from the app's own configured permission set, not a
 * runtime {@code scope} parameter — GitHub's authorize URL takes no {@code scope} at all for a GitHub App, unlike a
 * classic OAuth App or GitLab. This flow also never touches a GitHub App's private key (JWT-based app/installation auth
 * is a separate concern from this user-to-server linking flow) — only the app's client-id/client-secret, used solely
 * for the {@code /login/oauth/access_token} exchange.
 */
@Tag(name = "SCM OAuth", description = "Link the current user's account to an upstream SCM identity via OAuth")
@Slf4j
@RestController
@RequestMapping("/api/scm-oauth")
@RequiredArgsConstructor
public class ScmOAuthLinkController {

    private static final Set<String> SUPPORTED_PROVIDER_TYPES = Set.of("github", "gitlab", "forgejo");

    private final ReadOnlyUserStore userStore;

    private final FogwallConfig fogwallConfig;

    private final ProviderRegistry providerRegistry;

    private final TokenCipherProvider tokenCipherProvider;

    /** JDBC deployments only — empty on Mongo backends, where OAuth linking degrades to 501 (see linkStart). */
    private final Optional<ScmOAuthTokenStore> scmOAuthTokenStore;

    @Operation(operationId = "linkScmOAuth", summary = "Start the OAuth flow to link the current user's SCM account")
    @GetMapping("/{providerId}/link")
    public void link(@PathVariable String providerId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var linkable = requireLinkable(providerId, response);
        if (linkable == null) return;

        String state = UUID.randomUUID().toString();
        HttpSession session = request.getSession(true);
        session.setAttribute(stateSessionAttribute(providerId), state);

        String redirectUri = callbackUrl(providerId);
        ScmOAuthEndpoints.Endpoints endpoints = ScmOAuthEndpoints.resolve(linkable.provider());
        // response_type=code is required by GitLab's standard OAuth2 authorize endpoint ("Missing required
        // parameter: response_type" otherwise) — GitHub's is more lenient and defaults it, but sending it
        // explicitly is harmless there too (standard OAuth2 authorization-code flow for both).
        String authorizeUrl = endpoints.authorizeUrl()
                + "?client_id=" + encode(linkable.settings().getClientId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + scopeParam(linkable.provider())
                + "&state=" + encode(state);
        response.sendRedirect(authorizeUrl);
    }

    /**
     * fogwall's linking flow only ever reads {@code /user} and {@code /user/emails} — hardcode exactly the scope that
     * needs, rather than making it operator-configurable, so a misconfigured value can't request broader access than
     * the app actually uses. GitHub Apps take no {@code scope} parameter at all; their permissions come entirely from
     * the app's own configured account permissions.
     */
    private static String scopeParam(FogwallProvider provider) {
        return switch (provider.getType()) {
            case "gitlab" -> "&scope=read_user";
            case "forgejo" -> "&scope=read:user";
            default -> "";
        };
    }

    @Operation(operationId = "scmOAuthCallback", summary = "OAuth callback completing SCM account linking")
    @GetMapping("/{providerId}/callback")
    public void callback(
            @PathVariable String providerId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        // server.service-url is the bare host — the dashboard SPA is mounted at basename "/dashboard" (see App.tsx's
        // <BrowserRouter basename="/dashboard">), so the Profile route is /dashboard/profile.
        String profileUrl = fogwallConfig.getServer().getServiceUrl() + "/dashboard/profile";

        if (error != null) {
            log.warn("SCM OAuth callback for provider '{}' returned error: {}", providerId, error);
            response.sendRedirect(profileUrl + "?scmOAuthError=" + encode(error));
            return;
        }

        var linkable = requireLinkable(providerId, response);
        if (linkable == null) return;

        HttpSession session = request.getSession(false);
        Object expectedState = session != null ? session.getAttribute(stateSessionAttribute(providerId)) : null;
        if (expectedState == null || !expectedState.equals(state)) {
            log.warn("SCM OAuth callback for provider '{}' had an invalid or missing state parameter", providerId);
            response.sendRedirect(profileUrl + "?scmOAuthError=invalid_state");
            return;
        }
        session.removeAttribute(stateSessionAttribute(providerId));

        if (code == null) {
            response.sendRedirect(profileUrl + "?scmOAuthError=missing_code");
            return;
        }

        String currentUser = currentUsername();
        ScmOAuthEndpoints.Endpoints endpoints = ScmOAuthEndpoints.resolve(linkable.provider());
        try {
            TokenResponse tokenResponse = exchangeCodeForToken(endpoints, linkable.settings(), providerId, code);
            String scmUsername = fetchScmUsername(endpoints, linkable.provider(), tokenResponse.accessToken());

            var cipher = tokenCipherProvider.cipher().orElseThrow();
            byte[] encryptedAccessToken =
                    cipher.encrypt(tokenResponse.accessToken().getBytes(StandardCharsets.UTF_8));
            byte[] encryptedRefreshToken = tokenResponse.refreshToken() != null
                    ? cipher.encrypt(tokenResponse.refreshToken().getBytes(StandardCharsets.UTF_8))
                    : null;
            Instant expiresAt = tokenResponse.expiresInSeconds() != null
                    ? Instant.now().plusSeconds(tokenResponse.expiresInSeconds())
                    : null;

            if (!(userStore instanceof UserStore mutable)) {
                response.sendRedirect(profileUrl + "?scmOAuthError=store_not_mutable");
                return;
            }

            // Ensure the user has a proxy_users row before storing the token. user_scm_tokens (like user_ssh_keys and
            // repo_permissions) has an FK to proxy_users, but a config-declared user only gets a DB row when it is
            // referenced by a permission or group. OAuth linking is the one per-user store with no config-side
            // equivalent — tokens are encrypted secrets, DB-only — so a config user with no permission entry would
            // otherwise fail the FK on save. Materialize the row here with the same idempotent upsert the
            // permission/group paths already use.
            mutable.upsertUser(currentUser);

            if (scmOAuthTokenStore.isPresent()) {
                scmOAuthTokenStore
                        .get()
                        .save(
                                currentUser,
                                providerId,
                                encryptedAccessToken,
                                encryptedRefreshToken,
                                tokenResponse.scope(),
                                expiresAt);
            }
            mutable.upsertVerifiedScmIdentity(currentUser, providerId, scmUsername);
            lockProviderVerifiedEmails(
                    mutable, currentUser, providerId, linkable.provider(), tokenResponse.accessToken());
            importOAuthSshKeys(
                    mutable, currentUser, providerId, linkable.provider(), scmUsername, tokenResponse.accessToken());
            response.sendRedirect(profileUrl + "?scmOAuthLinked=" + encode(providerId));
        } catch (ScmIdentityConflictException e) {
            response.sendRedirect(profileUrl + "?scmOAuthError=identity_conflict");
        } catch (Exception e) {
            log.error("SCM OAuth linking failed for provider '{}' and user '{}'", providerId, currentUser, e);
            response.sendRedirect(profileUrl + "?scmOAuthError=link_failed");
        }
    }

    @Operation(operationId = "unlinkScmOAuth", summary = "Remove the current user's OAuth-linked SCM identity")
    @DeleteMapping("/{providerId}/unlink")
    public ResponseEntity<?> unlink(@PathVariable String providerId) {
        if (!(userStore instanceof UserStore mutable)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "SCM identity management requires a mutable user store"));
        }
        String currentUser = currentUsername();
        // Deliberately NOT userStore.findByUsername(currentUser).getScmIdentities() — for a config-declared user,
        // CompositeUserStore.findByUsername returns the config-only view and never reflects DB-added identities,
        // silently no-opping unlink. findScmIdentitiesWithVerified merges correctly.
        boolean hasVerifiedIdentity = mutable.findScmIdentitiesWithVerified(currentUser).stream()
                .anyMatch(id -> providerId.equalsIgnoreCase((String) id.get("provider"))
                        && Boolean.TRUE.equals(id.get("verified")));
        if (!hasVerifiedIdentity) {
            log.info(
                    "Unlink requested for provider '{}' by user '{}' but no OAuth-verified identity was found —"
                            + " nothing to do",
                    providerId,
                    currentUser);
            return ResponseEntity.noContent().build();
        }

        revokeUpstreamToken(providerId, currentUser);
        mutable.removeVerifiedScmIdentity(currentUser, providerId);
        mutable.removeSshKeysByAuthSource(currentUser, providerId);
        mutable.removeEmailsByAuthSource(currentUser, providerId);
        scmOAuthTokenStore.ifPresent(store -> store.remove(currentUser, providerId));
        log.info(
                "Unlinked SCM OAuth identity for user '{}' / provider '{}' (ssh keys and provider-verified emails"
                        + " removed)",
                currentUser,
                providerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Best-effort revocation of the upstream OAuth token before local cleanup (#40) — a failure here (network, expired
     * token, missing key) never blocks the local unlink, since the whole point of unlink is that fogwall stops
     * trusting/using this token regardless of whether the provider itself acknowledges the revocation.
     */
    private void revokeUpstreamToken(String providerId, String username) {
        if (scmOAuthTokenStore.isEmpty()) return;
        Optional<FogwallProvider> provider = providerRegistry.getProvider(providerId);
        OAuthProviderSettings settings = oauthSettingsFor(providerId);
        if (provider.isEmpty() || settings == null || settings.getClientId().isBlank()) return;
        try {
            Optional<byte[]> encrypted = scmOAuthTokenStore.get().findAccessToken(username, providerId);
            if (encrypted.isEmpty()) return;
            var cipher = tokenCipherProvider.cipher();
            if (cipher.isEmpty()) {
                log.warn(
                        "Skipping upstream token revocation for user '{}' / provider '{}': token encryption key"
                                + " unavailable",
                        username,
                        providerId);
                return;
            }
            String accessToken = new String(cipher.get().decrypt(encrypted.get()), StandardCharsets.UTF_8);
            String clientSecret = readClientSecret(settings);
            if (provider.get() instanceof GitHubProvider github) {
                String credentials = Base64.getEncoder()
                        .encodeToString((settings.getClientId() + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
                Request.delete(github.getApiUrl() + "/applications/" + settings.getClientId() + "/token")
                        .addHeader("Authorization", "Basic " + credentials)
                        .addHeader("Accept", "application/vnd.github+json")
                        .bodyString("{\"access_token\":\"" + accessToken + "\"}", ContentType.APPLICATION_JSON)
                        .execute(FogwallHttpExecutor.instance());
            } else if (provider.get() instanceof GitLabProvider gitlab) {
                var form = Form.form()
                        .add("client_id", settings.getClientId())
                        .add("client_secret", clientSecret)
                        .add("token", accessToken)
                        .build();
                Request.post(gitlab.getOAuthUrl() + "/revoke").bodyForm(form).execute(FogwallHttpExecutor.instance());
            }
            log.info("Revoked upstream OAuth token for user '{}' / provider '{}'", username, providerId);
        } catch (Exception e) {
            log.warn(
                    "Failed to revoke upstream OAuth token for user '{}' / provider '{}' (local unlink proceeds"
                            + " anyway): {}",
                    username,
                    providerId,
                    e.getMessage());
        }
    }

    private record Linkable(FogwallProvider provider, OAuthProviderSettings settings) {}

    private OAuthProviderSettings oauthSettingsFor(String providerId) {
        ProviderConfig providerConfig = fogwallConfig.getProviders().get(providerId);
        return providerConfig != null ? providerConfig.getOauth() : null;
    }

    private Linkable requireLinkable(String providerId, HttpServletResponse response) throws IOException {
        Optional<FogwallProvider> provider = providerRegistry.getProvider(providerId);
        if (provider.isEmpty()
                || !SUPPORTED_PROVIDER_TYPES.contains(provider.get().getType())) {
            response.sendError(
                    HttpStatus.NOT_FOUND.value(), "SCM OAuth linking does not support provider '" + providerId + "'");
            return null;
        }
        if (!tokenCipherProvider.isAvailable()) {
            response.sendError(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "SCM OAuth linking is temporarily unavailable (token encryption key not configured) — contact an administrator.");
            return null;
        }
        if (fogwallConfig.getServer().getServiceUrl() == null
                || fogwallConfig.getServer().getServiceUrl().isBlank()) {
            // Without this, callbackUrl()/profileUrl below would silently build a "null/..." URL instead of failing
            // clearly — server.service-url must be an externally-reachable base URL for the OAuth redirect to work.
            log.error(
                    "SCM OAuth linking requested for provider '{}' but server.service-url is not configured — "
                            + "cannot build a redirect_uri",
                    providerId);
            response.sendError(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "SCM OAuth linking is not configured (server.service-url is unset) — contact an administrator.");
            return null;
        }
        if (scmOAuthTokenStore.isEmpty()) {
            // Both database families provide a token store, so reaching here means neither was configured — a
            // deployment with no database at all. Degrade rather than failing Spring context startup.
            response.sendError(
                    HttpStatus.NOT_IMPLEMENTED.value(),
                    "SCM OAuth linking requires a database (JDBC or MongoDB); none is configured.");
            return null;
        }
        OAuthProviderSettings providerSettings = oauthSettingsFor(providerId);
        if (providerSettings == null
                || !providerSettings.isEnabled()
                || providerSettings.getClientId().isBlank()) {
            response.sendError(
                    HttpStatus.NOT_FOUND.value(), "Provider '" + providerId + "' is not configured for OAuth linking");
            return null;
        }
        return new Linkable(provider.get(), providerSettings);
    }

    private TokenResponse exchangeCodeForToken(
            ScmOAuthEndpoints.Endpoints endpoints,
            OAuthProviderSettings providerSettings,
            String providerId,
            String code)
            throws Exception {
        String clientSecret = readClientSecret(providerSettings);
        var form = Form.form()
                .add("client_id", providerSettings.getClientId())
                .add("client_secret", clientSecret)
                .add("code", code)
                .add("redirect_uri", callbackUrl(providerId))
                .add("grant_type", "authorization_code")
                .build();
        String responseBody = Request.post(endpoints.tokenUrl())
                .addHeader("Accept", "application/json")
                .bodyForm(form)
                .execute(FogwallHttpExecutor.instance())
                .returnContent()
                .asString();
        return new JsonMapper().readValue(responseBody, TokenResponse.class);
    }

    private String fetchScmUsername(ScmOAuthEndpoints.Endpoints endpoints, FogwallProvider provider, String accessToken)
            throws Exception {
        String authHeader = "github".equals(provider.getType()) ? "token " + accessToken : "Bearer " + accessToken;
        String responseBody = Request.get(endpoints.userApiUrl())
                .addHeader("Authorization", authHeader)
                .execute(FogwallHttpExecutor.instance())
                .returnContent()
                .asString();
        ScmUserInfoResponse userInfo = new JsonMapper().readValue(responseBody, ScmUserInfoResponse.class);
        // GitHub and Forgejo/Gitea return the account handle as "login"; GitLab returns it as "username".
        String scmUsername = "gitlab".equals(provider.getType()) ? userInfo.username() : userInfo.login();
        if (scmUsername == null || scmUsername.isBlank()) {
            throw new IllegalStateException("Provider user API response did not include a username");
        }
        return scmUsername;
    }

    /**
     * Locks in every email the SCM provider itself reports as verified, via the same {@code upsertLockedEmail}
     * mechanism already used for OIDC-provided emails — reduces developer toil (users already register these manually
     * today) without touching {@code commit.attribution-policy} enforcement, which is a separate concern. Never blocks
     * the overall link on a per-email conflict — logs and moves on to the next email.
     */
    private void lockProviderVerifiedEmails(
            UserStore mutable, String username, String providerId, FogwallProvider provider, String accessToken) {
        for (String email : fetchVerifiedEmails(provider, accessToken)) {
            try {
                mutable.upsertLockedEmail(username, email, providerId);
            } catch (EmailConflictException e) {
                log.warn(
                        "Skipping OAuth-verified email '{}' for user '{}' — already claimed by '{}'",
                        email,
                        username,
                        e.getOwner());
            }
        }
    }

    /**
     * One-time import of the user's provider-registered SSH public keys at link time (#40) — covered by the "Git SSH
     * keys: Read-only" account permission on the GitHub App, or the equivalent GitLab OAuth scope. Imported keys are
     * locked (not removable via the dashboard, same trust tier as {@link #lockProviderVerifiedEmails}) since they are
     * proven, not self-asserted. No ongoing background sync/reconciliation of keys removed upstream afterward — this
     * only runs at link time.
     */
    private void importOAuthSshKeys(
            UserStore mutable,
            String username,
            String providerId,
            FogwallProvider provider,
            String scmUsername,
            String accessToken) {
        userStore
                .findByUsername(username)
                .ifPresent(user -> ScmSshKeyImporter.reconcile(
                        mutable, user, providerId, ScmSshKeyImporter.fetch(provider, scmUsername, accessToken)));
    }

    private List<String> fetchVerifiedEmails(FogwallProvider provider, String accessToken) {
        try {
            if (provider instanceof GitHubProvider github) {
                String responseBody = Request.get(github.getApiUrl() + "/user/emails")
                        .addHeader("Authorization", "token " + accessToken)
                        .execute(FogwallHttpExecutor.instance())
                        .returnContent()
                        .asString();
                return verifiedGitHubEmails(new JsonMapper().readValue(responseBody, GitHubEmailEntry[].class));
            }
            if (provider instanceof GitLabProvider gitlab) {
                return fetchGitLabVerifiedEmails(gitlab, accessToken);
            }
            if (provider instanceof ForgejoProvider forgejo) {
                String responseBody = Request.get(forgejo.getApiUrl() + "/user/emails")
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .execute(FogwallHttpExecutor.instance())
                        .returnContent()
                        .asString();
                return verifiedForgejoEmails(new JsonMapper().readValue(responseBody, ForgejoEmailEntry[].class));
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to fetch verified emails from provider '{}': {}", provider.getType(), e.getMessage());
            return List.of();
        }
    }

    /**
     * GitLab's primary account email (via {@code /user}) plus every additional confirmed email (via
     * {@code /user/emails}, each with its own {@code confirmed_at}) — mirrors GitHub's single {@code /user/emails}
     * call, which already returns primary + secondary together with a per-email {@code verified} flag. Merged and
     * deduplicated since it isn't confirmed whether GitLab's {@code /user/emails} list also includes the primary.
     */
    private List<String> fetchGitLabVerifiedEmails(GitLabProvider gitlab, String accessToken) throws Exception {
        Set<String> verified = new LinkedHashSet<>();

        // GitLab requires the primary account email to be confirmed for the account to be usable under most
        // deployment configurations — treated as verified without a separate confirmation check.
        String userResponseBody = Request.get(gitlab.getApiUrl() + "/user")
                .addHeader("Authorization", "Bearer " + accessToken)
                .execute(FogwallHttpExecutor.instance())
                .returnContent()
                .asString();
        GitLabUserResponse user = new JsonMapper().readValue(userResponseBody, GitLabUserResponse.class);
        if (user.email() != null && !user.email().isBlank()) {
            verified.add(user.email());
        }

        String emailsResponseBody = Request.get(gitlab.getApiUrl() + "/user/emails")
                .addHeader("Authorization", "Bearer " + accessToken)
                .execute(FogwallHttpExecutor.instance())
                .returnContent()
                .asString();
        GitLabEmailEntry[] entries = new JsonMapper().readValue(emailsResponseBody, GitLabEmailEntry[].class);
        verified.addAll(verifiedGitLabEmails(entries));

        return List.copyOf(verified);
    }

    /** Package-visible for unit testing without a live HTTP call — the actual verification-status filtering logic. */
    static List<String> verifiedGitHubEmails(GitHubEmailEntry[] entries) {
        return Arrays.stream(entries)
                .filter(GitHubEmailEntry::verified)
                .map(GitHubEmailEntry::email)
                .toList();
    }

    /** Package-visible for unit testing without a live HTTP call — the actual verification-status filtering logic. */
    static List<String> verifiedGitLabEmails(GitLabEmailEntry[] entries) {
        return Arrays.stream(entries)
                .filter(e -> e.confirmedAt() != null && !e.confirmedAt().isBlank())
                .map(GitLabEmailEntry::email)
                .toList();
    }

    /** Package-visible for unit testing without a live HTTP call — the actual verification-status filtering logic. */
    static List<String> verifiedForgejoEmails(ForgejoEmailEntry[] entries) {
        return Arrays.stream(entries)
                .filter(ForgejoEmailEntry::verified)
                .map(ForgejoEmailEntry::email)
                .toList();
    }

    private String readClientSecret(OAuthProviderSettings providerSettings) throws IOException {
        return Files.readString(Path.of(providerSettings.getClientSecretPath())).strip();
    }

    private String callbackUrl(String providerId) {
        return fogwallConfig.getServer().getServiceUrl() + "/api/scm-oauth/" + providerId + "/callback";
    }

    private static String stateSessionAttribute(String providerId) {
        return "scm-oauth-state-" + providerId;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("scope") String scope,
            @JsonProperty("expires_in") Long expiresInSeconds) {}

    /** Deserialization target for the GitHub/GitLab {@code GET /user} response (login vs username field). */
    record ScmUserInfoResponse(String login, String username) {}

    /** Deserialization target for one entry of GitHub's {@code GET /user/emails} response. */
    record GitHubEmailEntry(String email, boolean verified, boolean primary) {}

    /** Deserialization target for GitLab's {@code GET /user} response — only the field this class needs. */
    record GitLabUserResponse(String email) {}

    /** Deserialization target for one entry of GitLab's {@code GET /user/emails} response. */
    record GitLabEmailEntry(
            String email, @JsonProperty("confirmed_at") String confirmedAt) {}

    /** Deserialization target for one entry of Forgejo/Gitea's {@code GET /api/v1/user/emails} response. */
    record ForgejoEmailEntry(String email, boolean verified, boolean primary) {}

    /** Deserialization target for one entry of GitHub's or Forgejo/Gitea's {@code GET .../user/keys} response. */
}
