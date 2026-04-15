package org.finos.gitproxy.dashboard;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.jetty.config.AdAuthConfig;
import org.finos.gitproxy.jetty.config.AuthConfig;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.LdapAuthConfig;
import org.finos.gitproxy.jetty.config.OidcAuthConfig;
import org.finos.gitproxy.user.EmailConflictException;
import org.finos.gitproxy.user.ReadOnlyUserStore;
import org.finos.gitproxy.user.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration for the dashboard module.
 *
 * <p>The active authentication provider is selected from {@code auth.provider} in {@code git-proxy.yml}:
 *
 * <ul>
 *   <li>{@code local} (default) — form login validated against BCrypt password hashes in {@code users:}
 *   <li>{@code ldap} — form login with generic LDAP bind authentication; settings in {@code auth.ldap}
 *   <li>{@code ad} — form login with Active Directory UPN bind; settings in {@code auth.ad}
 *   <li>{@code oidc} — OpenID Connect authorization code flow; settings in {@code auth.oidc}
 * </ul>
 *
 * <p>Git paths ({@code /push/**}, {@code /proxy/**}) are never matched by the security filter chain so git clients are
 * not redirected to a login page.
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private ReadOnlyUserStore userStore;

    @Autowired
    private GitProxyConfig gitProxyConfig;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthConfig authCfg = gitProxyConfig.getAuth();
        String provider = authCfg.getProvider();
        log.info("Authentication provider: {}", provider);

        boolean isIdpProvider = !"local".equals(provider);
        String apiKey = System.getenv("GITPROXY_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("API key authentication enabled (operator-supplied)");
            http.addFilterBefore(new ApiKeyAuthFilter(apiKey), UsernamePasswordAuthenticationFilter.class);
        } else if (isIdpProvider) {
            String generated = generateBreakGlassKey();
            writeBreakGlassToken(generated);
            log.warn("IdP auth active — local user store disabled. Auto-generated break-glass API key written to"
                    + " break-glass.token (chmod 600). Use X-Api-Key header for emergency admin API access."
                    + " Not persisted across restarts. Pin a stable key with GITPROXY_API_KEY env var.");
            http.addFilterBefore(new ApiKeyAuthFilter(generated), UsernamePasswordAuthenticationFilter.class);
        }

        List<String> allowedOrigins = gitProxyConfig.getServer().getAllowedOrigins();
        if (!allowedOrigins.isEmpty()) {
            log.info("CORS enabled for origins: {}", allowedOrigins);
            http.cors(cors -> cors.configurationSource(corsConfigurationSource(allowedOrigins)));
        }

        // For OIDC, the Spring Security filter chain must also cover the OAuth2 redirect/callback
        // paths. These are never registered on git servlet paths, so there is no risk of conflict.
        String[] protectedPaths = "oidc".equals(provider)
                ? new String[] {"/api/**", "/login", "/logout", "/oauth2/**", "/login/oauth2/**"}
                : new String[] {"/api/**", "/login", "/logout"};

        http.securityMatcher(protectedPaths)
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/runtime-config", "/api/health")
                        .permitAll()
                        .requestMatchers("/api/users", "/api/users/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/repos/rules/**")
                        .hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/repos/rules")
                        .hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/config/reload")
                        .hasRole("ADMIN")
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST, "/api/push/*/authorise", "/api/push/*/reject")
                        .authenticated()
                        .anyRequest()
                        .authenticated())
                .logout(logout -> logout.logoutSuccessUrl("/login.html?logout").permitAll())
                // Return 401 to SPA fetch() calls instead of redirecting to the login page.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        (req, res, e) -> res.sendError(401),
                        req -> req.getServletPath().startsWith("/api/")));

        var successHandler = idpProvisioningSuccessHandler();
        switch (provider) {
            case "ldap" -> configureLdapAuth(http, authCfg.getLdap(), successHandler, authCfg.getRoleMappings());
            case "ad" -> configureAdAuth(http, authCfg.getAd(), successHandler, authCfg.getRoleMappings());
            case "oidc" ->
                configureOidcAuth(
                        http, authCfg.getOidc(), successHandler, authCfg.getRoleMappings(), authCfg.getGroupsClaim());
            case "local" -> configureLocalAuth(http, successHandler);
            default ->
                throw new IllegalStateException(
                        "Unknown auth.provider '" + provider + "'. Valid values: local, ldap, ad, oidc.");
        }

        return http.build();
    }

    @Bean
    HttpSessionListener sessionTimeoutListener() {
        int timeoutSeconds = (int) gitProxyConfig.getAuth().getSessionTimeoutSeconds();
        log.info("Session timeout: {} seconds", timeoutSeconds);
        return new HttpSessionListener() {
            @Override
            public void sessionCreated(HttpSessionEvent event) {
                event.getSession().setMaxInactiveInterval(timeoutSeconds);
            }
        };
    }

    // ── Local (default) ─────────────────────────────────────────────────────────

    private void configureLocalAuth(HttpSecurity http, AuthenticationSuccessHandler successHandler) throws Exception {
        DaoAuthenticationProvider dao = new DaoAuthenticationProvider(staticUserDetailsService());
        dao.setPasswordEncoder(passwordEncoder());

        http.authenticationProvider(dao)
                .formLogin(form -> form.loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler(successHandler)
                        .permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/login")
                        .ignoringRequestMatchers("/api/health", "/api/")
                        .ignoringRequestMatchers(req -> req.getHeader("X-Api-Key") != null));
    }

    // ── LDAP ────────────────────────────────────────────────────────────────────

    private void configureLdapAuth(
            HttpSecurity http,
            LdapAuthConfig ldapCfg,
            AuthenticationSuccessHandler successHandler,
            Map<String, List<String>> roleMappings)
            throws Exception {
        if (ldapCfg.getUrl().isBlank()) {
            throw new IllegalStateException("auth.provider=ldap requires auth.ldap.url to be set in git-proxy.yml");
        }

        var contextSource = new DefaultSpringSecurityContextSource(ldapCfg.getUrl());
        if (!ldapCfg.getBindDn().isBlank()) {
            contextSource.setUserDn(ldapCfg.getBindDn());
            contextSource.setPassword(ldapCfg.getBindPassword());
        }
        contextSource.afterPropertiesSet();

        var authenticator = new BindAuthenticator(contextSource);
        if (!ldapCfg.getUserSearchFilter().isBlank()) {
            var userSearch = new FilterBasedLdapUserSearch(
                    ldapCfg.getUserSearchBase(), ldapCfg.getUserSearchFilter(), contextSource);
            userSearch.setSearchSubtree(true);
            authenticator.setUserSearch(userSearch);
        } else {
            authenticator.setUserDnPatterns(new String[] {ldapCfg.getUserDnPatterns()});
        }
        authenticator.setUserAttributes(new String[] {"mail"});
        authenticator.afterPropertiesSet();

        LdapAuthenticationProvider ldapProvider;
        if (!ldapCfg.getGroupSearchBase().isBlank()) {
            var populator = new DefaultLdapAuthoritiesPopulator(contextSource, ldapCfg.getGroupSearchBase());
            populator.setGroupSearchFilter(ldapCfg.getGroupSearchFilter());
            populator.setRolePrefix("");
            populator.setConvertToUpperCase(false);
            populator.setSearchSubtree(true);
            ldapProvider = new LdapAuthenticationProvider(authenticator, populator);
            ldapProvider.setAuthoritiesMapper(ldapAuthorities -> mapIdpGroupsToRoles(ldapAuthorities, roleMappings));
            log.info(
                    "LDAP group search enabled: base={}, filter={}",
                    ldapCfg.getGroupSearchBase(),
                    ldapCfg.getGroupSearchFilter());
        } else {
            ldapProvider = new LdapAuthenticationProvider(authenticator);
        }
        ldapProvider.setUserDetailsContextMapper(new LdapEmailContextMapper());

        http.authenticationProvider(ldapProvider)
                .formLogin(form -> form.loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler(successHandler)
                        .permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/login")
                        .ignoringRequestMatchers("/api/health", "/api/")
                        .ignoringRequestMatchers(req -> req.getHeader("X-Api-Key") != null));

        if (!ldapCfg.getUserSearchFilter().isBlank()) {
            log.info(
                    "LDAP authentication configured: url={}, userSearchFilter={}, userSearchBase={}",
                    ldapCfg.getUrl(),
                    ldapCfg.getUserSearchFilter(),
                    ldapCfg.getUserSearchBase());
        } else {
            log.info(
                    "LDAP authentication configured: url={}, userDnPatterns={}",
                    ldapCfg.getUrl(),
                    ldapCfg.getUserDnPatterns());
        }
    }

    // ── Active Directory ────────────────────────────────────────────────────────

    private void configureAdAuth(
            HttpSecurity http,
            AdAuthConfig adCfg,
            AuthenticationSuccessHandler successHandler,
            Map<String, List<String>> roleMappings)
            throws Exception {
        if (adCfg.getDomain().isBlank()) {
            throw new IllegalStateException("auth.provider=ad requires auth.ad.domain to be set in git-proxy.yml");
        }

        String adUrl = adCfg.getUrl().isBlank() ? null : adCfg.getUrl();
        var adProvider =
                new org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider(
                        adCfg.getDomain(), adUrl);

        if (!adCfg.getGroupSearchBase().isBlank()) {
            // Wire a DefaultLdapAuthoritiesPopulator for group-based role mapping.
            // AD bind uses UPN so we construct a context source bound to the domain controller URL.
            String contextUrl = adUrl != null ? adUrl : "ldap://" + adCfg.getDomain();
            var contextSource = new DefaultSpringSecurityContextSource(contextUrl);
            if (!adCfg.getBindDn().isBlank()) {
                contextSource.setUserDn(adCfg.getBindDn());
                contextSource.setPassword(adCfg.getBindPassword());
            }
            contextSource.afterPropertiesSet();

            var populator = new DefaultLdapAuthoritiesPopulator(contextSource, adCfg.getGroupSearchBase());
            populator.setGroupSearchFilter(adCfg.getGroupSearchFilter());
            populator.setRolePrefix("");
            populator.setConvertToUpperCase(false);
            populator.setSearchSubtree(true);
            adProvider.setAuthoritiesPopulator(populator);
            adProvider.setAuthoritiesMapper(ldapAuthorities -> mapIdpGroupsToRoles(ldapAuthorities, roleMappings));
            log.info(
                    "AD group search enabled: base={}, filter={}",
                    adCfg.getGroupSearchBase(),
                    adCfg.getGroupSearchFilter());
        }

        adProvider.setUserDetailsContextMapper(new LdapEmailContextMapper());

        http.authenticationProvider(adProvider)
                .formLogin(form -> form.loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler(successHandler)
                        .permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/login")
                        .ignoringRequestMatchers("/api/health", "/api/")
                        .ignoringRequestMatchers(req -> req.getHeader("X-Api-Key") != null));

        log.info("Active Directory authentication configured: domain={}, url={}", adCfg.getDomain(), adUrl);
    }

    // ── OIDC ────────────────────────────────────────────────────────────────────

    private void configureOidcAuth(
            HttpSecurity http,
            OidcAuthConfig oidcCfg,
            AuthenticationSuccessHandler successHandler,
            Map<String, List<String>> roleMappings,
            String groupsClaim)
            throws Exception {
        if (oidcCfg.getIssuerUri().isBlank() || oidcCfg.getClientId().isBlank()) {
            throw new IllegalStateException(
                    "auth.provider=oidc requires auth.oidc.issuer-uri and auth.oidc.client-id in git-proxy.yml");
        }

        boolean usePrivateKeyJwt = !oidcCfg.getPrivateKeyPath().isBlank();
        ClientAuthenticationMethod authMethod = usePrivateKeyJwt
                ? ClientAuthenticationMethod.PRIVATE_KEY_JWT
                : ClientAuthenticationMethod.CLIENT_SECRET_BASIC;

        ClientRegistration registration;
        if (!oidcCfg.getJwkSetUri().isBlank()) {
            // Manual registration: skip OIDC discovery and issuer validation. Used when the provider's
            // reported issuer URL does not match the URL used to reach it (e.g. Entra ID, whose tokens
            // carry iss=https://sts.windows.net/{tenant}/ rather than the discovery base URL).
            // Endpoint paths follow the standard OIDC convention relative to issuerUri.
            var builder = ClientRegistration.withRegistrationId("gitproxy")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .clientId(oidcCfg.getClientId())
                    .authorizationUri(oidcCfg.getIssuerUri() + "/authorize")
                    .tokenUri(
                            oidcCfg.getTokenUri().isBlank() ? oidcCfg.getIssuerUri() + "/token" : oidcCfg.getTokenUri())
                    .jwkSetUri(oidcCfg.getJwkSetUri())
                    .userInfoUri(
                            oidcCfg.getUserInfoUri().isBlank()
                                    ? oidcCfg.getIssuerUri() + "/userinfo"
                                    : oidcCfg.getUserInfoUri())
                    .userNameAttributeName(oidcCfg.getUserNameAttribute())
                    .scope("openid", "profile", "email")
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .clientAuthenticationMethod(authMethod);
            if (!usePrivateKeyJwt) {
                builder.clientSecret(oidcCfg.getClientSecret());
            }
            registration = builder.build();
        } else {
            // Standard path: fetch OIDC discovery document at startup. The OIDC server must be reachable
            // when the Spring context initializes (in Docker Compose this is guaranteed via depends_on).
            var builder = ClientRegistrations.fromIssuerLocation(oidcCfg.getIssuerUri())
                    .registrationId("gitproxy")
                    .clientId(oidcCfg.getClientId())
                    .scope("openid", "profile", "email")
                    .userNameAttributeName(oidcCfg.getUserNameAttribute())
                    .clientAuthenticationMethod(authMethod);
            if (!usePrivateKeyJwt) {
                builder.clientSecret(oidcCfg.getClientSecret());
            }
            registration = builder.build();
        }

        http.oauth2Login(oauth2 -> {
                    oauth2.clientRegistrationRepository(new InMemoryClientRegistrationRepository(registration))
                            .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                            .successHandler(successHandler)
                            .failureUrl("/login.html?error")
                            .userInfoEndpoint(userInfo ->
                                    userInfo.oidcUserService(buildOidcUserService(roleMappings, groupsClaim)));

                    if (usePrivateKeyJwt) {
                        RSAKey rsaKey =
                                loadRsaKey(oidcCfg.getPrivateKeyPath(), oidcCfg.getCertPath(), oidcCfg.getKeyId());
                        Function<ClientRegistration, JWK> jwkResolver = reg ->
                                ClientAuthenticationMethod.PRIVATE_KEY_JWT.equals(reg.getClientAuthenticationMethod())
                                        ? rsaKey
                                        : null;
                        NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest>
                                converter = new NimbusJwtClientAuthenticationParametersConverter<>(jwkResolver);
                        // NimbusJwtClientAuthenticationParametersConverter only propagates kid to the JWT
                        // header — x5t#S256 is never copied from the JWK. Entra ID matches registered
                        // certificates by x5t#S256 (SHA-256 thumbprint), so inject it explicitly when present.
                        if (rsaKey.getX509CertSHA256Thumbprint() != null) {
                            var x5tS256 = rsaKey.getX509CertSHA256Thumbprint().toString();
                            converter.setJwtClientAssertionCustomizer(
                                    ctx -> ctx.getHeaders().header("x5t#S256", x5tS256));
                        }
                        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
                        tokenResponseClient.addParametersConverter(converter);
                        oauth2.tokenEndpoint(token -> token.accessTokenResponseClient(tokenResponseClient));
                    }
                })
                // CSRF: state parameter in OAuth2 flow protects the callback; exclude it from
                // cookie-based CSRF checks. The /api/** endpoints remain CSRF-protected.
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/login/oauth2/code/**")
                        .ignoringRequestMatchers("/api/health", "/api/")
                        .ignoringRequestMatchers(req -> req.getHeader("X-Api-Key") != null));

        log.info(
                "OIDC authentication configured: issuerUri={}, clientId={}, jwkSetUri={}, clientAuth={}",
                oidcCfg.getIssuerUri(),
                oidcCfg.getClientId(),
                oidcCfg.getJwkSetUri().isBlank() ? "(discovered)" : oidcCfg.getJwkSetUri(),
                authMethod.getValue());
        log.info("OIDC role-mappings: {}, groups-claim: {}", roleMappings, groupsClaim);
    }

    // ── IdP user provisioning ────────────────────────────────────────────────────

    /**
     * Returns a success handler that auto-provisions IdP users and locks their email address on first login, then
     * delegates to the standard saved-request redirect. For local (static) auth the provisioning step is a no-op.
     */
    private AuthenticationSuccessHandler idpProvisioningSuccessHandler() {
        var delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/dashboard/");
        delegate.setAlwaysUseDefaultTargetUrl(true);
        return new AuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(
                    HttpServletRequest request, HttpServletResponse response, Authentication auth)
                    throws IOException, ServletException {
                provisionIdpUser(auth);
                delegate.onAuthenticationSuccess(request, response, auth);
            }
        };
    }

    private void provisionIdpUser(Authentication auth) {
        if (!(userStore instanceof UserStore jdbc)) return;

        String username = auth.getName();
        String email = null;
        String authSource = null;

        if (auth.getPrincipal() instanceof OidcUser oidcUser) {
            email = oidcUser.getEmail();
            authSource = "oidc";
        } else if (auth.getPrincipal() instanceof LdapUserDetailsWithEmail ldapUser) {
            email = ldapUser.getEmail();
            authSource = "ldap";
        }

        if (authSource == null) return;

        jdbc.upsertUser(username);
        if (email != null && !email.isBlank()) {
            try {
                jdbc.upsertLockedEmail(username, email.strip().toLowerCase(), authSource);
                log.info("Locked {} email '{}' for user '{}'", authSource, email, username);
            } catch (EmailConflictException e) {
                log.warn(
                        "IdP login for '{}' via {}: email '{}' is already claimed by user '{}' — skipping email lock",
                        username,
                        authSource,
                        email,
                        e.getOwner());
            }
        } else {
            log.warn("IdP login for '{}' via {} returned no email address", username, authSource);
        }
    }

    /**
     * Builds an {@link OidcUserService} that maps IdP group memberships to git-proxy-java roles. The configured
     * {@code groupsClaim} is read from the OIDC token; any group present in {@code roleMappings} results in a
     * corresponding {@code ROLE_xxx} authority being added to the session.
     *
     * <p>If {@code roleMappings} is empty, {@code ROLE_USER} is granted to every authenticated user (open mode). If
     * {@code roleMappings} is non-empty, access is <em>deny-by-default</em>: the user must belong to at least one
     * mapped group, otherwise authentication is rejected.
     */
    private OidcUserService buildOidcUserService(Map<String, List<String>> roleMappings, String groupsClaim) {
        return new OidcUserService() {
            @Override
            public OidcUser loadUser(OidcUserRequest userRequest) {
                OidcUser oidcUser = super.loadUser(userRequest);
                String nameAttributeKey = userRequest
                        .getClientRegistration()
                        .getProviderDetails()
                        .getUserInfoEndpoint()
                        .getUserNameAttributeName();
                if (roleMappings.isEmpty()) {
                    Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                    return new DefaultOidcUser(
                            authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), nameAttributeKey);
                }
                List<String> groups = oidcUser.getClaimAsStringList(groupsClaim);
                if (groups == null) groups = List.of();
                Set<GrantedAuthority> authorities = new LinkedHashSet<>();
                for (Map.Entry<String, List<String>> entry : roleMappings.entrySet()) {
                    List<String> mappedGroups = entry.getValue();
                    if (groups.stream().anyMatch(mappedGroups::contains)) {
                        String role = entry.getKey().toUpperCase(java.util.Locale.ROOT);
                        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                        log.debug("Granted ROLE_{} to OIDC user '{}' via group membership", role, oidcUser.getName());
                    }
                }
                if (authorities.isEmpty()) {
                    log.warn("OIDC login denied for '{}': not a member of any authorised group", oidcUser.getName());
                    throw new OAuth2AuthenticationException(
                            new OAuth2Error("access_denied"),
                            "Access not granted: your account is not a member of any authorised group");
                }
                return new DefaultOidcUser(
                        authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), nameAttributeKey);
            }
        };
    }

    /**
     * Maps IdP-supplied group authorities (from LDAP/AD group search) to git-proxy-java roles using
     * {@code roleMappings}.
     *
     * <p>If {@code roleMappings} is empty the operator has not configured group-based access control, so
     * {@code ROLE_USER} is granted to every authenticated user (open mode). If {@code roleMappings} is non-empty,
     * access is <em>deny-by-default</em>: the user must be a member of at least one mapped group, otherwise
     * authentication is rejected with {@link BadCredentialsException}.
     */
    private Set<GrantedAuthority> mapIdpGroupsToRoles(
            Collection<? extends GrantedAuthority> ldapAuthorities, Map<String, List<String>> roleMappings) {
        if (roleMappings.isEmpty()) {
            return Set.of(new SimpleGrantedAuthority("ROLE_USER"));
        }
        Set<GrantedAuthority> mapped = new LinkedHashSet<>();
        for (GrantedAuthority authority : ldapAuthorities) {
            String groupName = authority.getAuthority();
            for (Map.Entry<String, List<String>> entry : roleMappings.entrySet()) {
                if (entry.getValue().contains(groupName)) {
                    mapped.add(new SimpleGrantedAuthority("ROLE_USER"));
                    mapped.add(
                            new SimpleGrantedAuthority("ROLE_" + entry.getKey().toUpperCase(java.util.Locale.ROOT)));
                }
            }
        }
        if (mapped.isEmpty()) {
            log.warn("IdP login denied: user is not a member of any authorised group");
            throw new BadCredentialsException(
                    "Access not granted: your account is not a member of any authorised group");
        }
        return mapped;
    }

    /**
     * Loads an RSA private key from a PKCS#8 PEM file and wraps it in a Nimbus {@link RSAKey} for use with
     * {@link NimbusJwtClientAuthenticationParametersConverter}. The public key is derived from the CRT parameters
     * embedded in the private key — no separate public key file is needed.
     *
     * <p>Key-ID precedence when {@code private-key-path} is set:
     *
     * <ol>
     *   <li>{@code certPath} non-blank → SHA-256 thumbprint set as {@code x5t#S256} (Entra ID)
     *   <li>{@code keyId} non-blank → used as explicit {@code kid} (Keycloak, Okta, Auth0, Dex)
     *   <li>Neither → random UUID {@code kid} (suitable only for providers that accept any {@code kid})
     * </ol>
     *
     * <p>Generate a suitable key pair with:
     *
     * <pre>
     * openssl genrsa -out private.pem 2048
     * openssl pkcs8 -topk8 -nocrypt -in private.pem -out private-pkcs8.pem
     * openssl req -new -x509 -key private.pem -out cert.pem -days 365   # Entra ID only
     * </pre>
     */
    static RSAKey loadRsaKey(String pemPath, String certPath, String keyId) {
        try {
            String pem = Files.readString(Path.of(pemPath))
                    .replaceAll("-----[^-]+-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            var privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            var crtKey = (RSAPrivateCrtKey) privateKey;
            var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            var builder = new RSAKey.Builder(publicKey).privateKey((RSAPrivateKey) privateKey);
            if (certPath != null && !certPath.isBlank()) {
                try (var in = Files.newInputStream(Path.of(certPath))) {
                    var cert = (X509Certificate)
                            CertificateFactory.getInstance("X.509").generateCertificate(in);
                    byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
                    builder.x509CertSHA256Thumbprint(Base64URL.encode(sha256));
                }
            } else if (keyId != null && !keyId.isBlank()) {
                builder.keyID(keyId);
            } else {
                builder.keyID(UUID.randomUUID().toString());
            }
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA private key from: " + pemPath, e);
        }
    }

    /**
     * Custom ID token decoder factory that skips issuer ({@code iss}) claim validation when {@code jwkSetUri} is
     * explicitly configured. This is needed for providers whose reported issuer URL does not match the URL used to
     * reach them — notably Entra ID, which issues tokens with {@code iss=https://sts.windows.net/{tenant}/} regardless
     * of which discovery URL was used.
     *
     * <p>Spring Security's {@code OAuth2LoginConfigurer} automatically picks this factory up from the
     * {@code ApplicationContext} and uses it when decoding OIDC ID tokens.
     */
    @Bean
    public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        if (!gitProxyConfig.getAuth().getOidc().getJwkSetUri().isBlank()) {
            factory.setJwtValidatorFactory(reg -> new DelegatingOAuth2TokenValidator<Jwt>(new JwtTimestampValidator()));
        }
        return factory;
    }

    // ── Shared helpers ───────────────────────────────────────────────────────────

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private UserDetailsService staticUserDetailsService() {
        return username -> userStore
                .findByUsername(username)
                .map(u -> {
                    String[] roles = u.getRoles().isEmpty()
                            ? new String[] {"USER"}
                            : u.getRoles().toArray(String[]::new);
                    return User.withUsername(u.getUsername())
                            .password(u.getPasswordHash())
                            .roles(roles)
                            .build();
                })
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    static String generateBreakGlassKey() {
        byte[] bytes = new byte[24]; // 192 bits → 32 URL-safe base64 chars
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static void writeBreakGlassToken(String key) {
        writeBreakGlassToken(key, Path.of("break-glass.token"));
    }

    static void writeBreakGlassToken(String key, Path path) {
        try {
            Files.writeString(path, key + System.lineSeparator());
            try {
                Files.setPosixFilePermissions(
                        path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException e) {
                log.debug("POSIX permissions not supported; break-glass.token may have default file permissions");
            }
        } catch (IOException e) {
            // Non-fatal: log loudly but don't abort startup — the key is still active in memory
            log.error(
                    "Failed to write break-glass.token: {}. Break-glass API key is active but not on disk.",
                    e.getMessage());
        }
    }

    private CorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
        var corsConfig = new CorsConfiguration();
        corsConfig.setAllowedOrigins(allowedOrigins);
        corsConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        corsConfig.setAllowedHeaders(List.of("*"));
        corsConfig.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", corsConfig);
        return source;
    }
}
