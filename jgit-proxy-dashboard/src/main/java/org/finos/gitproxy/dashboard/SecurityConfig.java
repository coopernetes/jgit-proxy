package org.finos.gitproxy.dashboard;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.jetty.config.AuthConfig;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.jetty.config.LdapAuthConfig;
import org.finos.gitproxy.jetty.config.OidcAuthConfig;
import org.finos.gitproxy.user.JdbcUserStore;
import org.finos.gitproxy.user.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
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
 *   <li>{@code ldap} — form login with LDAP bind authentication; settings in {@code auth.ldap}
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
    private UserStore userStore;

    @Autowired
    private GitProxyConfig gitProxyConfig;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthConfig authCfg = gitProxyConfig.getAuth();
        String provider = authCfg.getProvider();
        log.info("Authentication provider: {}", provider);

        String apiKey = System.getenv("GITPROXY_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("API key authentication enabled");
            http.addFilterBefore(new ApiKeyAuthFilter(apiKey), UsernamePasswordAuthenticationFilter.class);
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
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/runtime-config")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .logout(logout -> logout.logoutSuccessUrl("/login.html?logout").permitAll())
                // Return 401 to SPA fetch() calls instead of redirecting to the login page.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        (req, res, e) -> res.sendError(401),
                        req -> req.getServletPath().startsWith("/api/")));

        var successHandler = idpProvisioningSuccessHandler();
        switch (provider) {
            case "ldap" -> configureLdapAuth(http, authCfg.getLdap(), successHandler);
            case "oidc" -> configureOidcAuth(http, authCfg.getOidc(), successHandler);
            case "local" -> configureLocalAuth(http, successHandler);
            default -> {
                log.warn("Unknown auth.provider '{}', falling back to local auth", provider);
                configureLocalAuth(http, successHandler);
            }
        }

        return http.build();
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
                        .ignoringRequestMatchers(req -> req.getHeader("X-Api-Key") != null));
    }

    // ── LDAP ────────────────────────────────────────────────────────────────────

    private void configureLdapAuth(
            HttpSecurity http, LdapAuthConfig ldapCfg, AuthenticationSuccessHandler successHandler) throws Exception {
        if (ldapCfg.getUrl().isBlank()) {
            throw new IllegalStateException("auth.provider=ldap requires auth.ldap.url to be set in git-proxy.yml");
        }

        var contextSource = new DefaultSpringSecurityContextSource(ldapCfg.getUrl());
        if (!ldapCfg.getManagerDn().isBlank()) {
            contextSource.setUserDn(ldapCfg.getManagerDn());
            contextSource.setPassword(ldapCfg.getManagerPassword());
        }
        contextSource.afterPropertiesSet();

        var authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserDnPatterns(new String[] {ldapCfg.getUserDnPatterns()});
        authenticator.setUserAttributes(new String[] {"mail"});
        authenticator.afterPropertiesSet();

        var ldapProvider = new LdapAuthenticationProvider(authenticator);
        ldapProvider.setUserDetailsContextMapper(new LdapEmailContextMapper());

        http.authenticationProvider(ldapProvider)
                .formLogin(form -> form.loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler(successHandler)
                        .permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/login")
                        .ignoringRequestMatchers(req -> req.getHeader("X-Api-Key") != null));

        log.info(
                "LDAP authentication configured: url={}, userDnPatterns={}",
                ldapCfg.getUrl(),
                ldapCfg.getUserDnPatterns());
    }

    // ── OIDC ────────────────────────────────────────────────────────────────────

    private void configureOidcAuth(
            HttpSecurity http, OidcAuthConfig oidcCfg, AuthenticationSuccessHandler successHandler) throws Exception {
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
                    .tokenUri(oidcCfg.getIssuerUri() + "/token")
                    .jwkSetUri(oidcCfg.getJwkSetUri())
                    .userInfoUri(oidcCfg.getIssuerUri() + "/userinfo")
                    .userNameAttributeName("preferred_username")
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
                    .clientAuthenticationMethod(authMethod);
            if (!usePrivateKeyJwt) {
                builder.clientSecret(oidcCfg.getClientSecret());
            }
            registration = builder.build();
        }

        http.oauth2Login(oauth2 -> {
                    oauth2.clientRegistrationRepository(new InMemoryClientRegistrationRepository(registration))
                            .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                            .successHandler(successHandler);

                    if (usePrivateKeyJwt) {
                        RSAKey rsaKey = loadRsaKey(oidcCfg.getPrivateKeyPath());
                        Function<ClientRegistration, JWK> jwkResolver = reg ->
                                ClientAuthenticationMethod.PRIVATE_KEY_JWT.equals(reg.getClientAuthenticationMethod())
                                        ? rsaKey
                                        : null;
                        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
                        tokenResponseClient.addParametersConverter(
                                new NimbusJwtClientAuthenticationParametersConverter<>(jwkResolver));
                        oauth2.tokenEndpoint(token -> token.accessTokenResponseClient(tokenResponseClient));
                    }
                })
                // CSRF: state parameter in OAuth2 flow protects the callback; exclude it from
                // cookie-based CSRF checks. The /api/** endpoints remain CSRF-protected.
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/login/oauth2/code/**")
                        .ignoringRequestMatchers(req -> req.getHeader("X-Api-Key") != null));

        log.info(
                "OIDC authentication configured: issuerUri={}, clientId={}, jwkSetUri={}, clientAuth={}",
                oidcCfg.getIssuerUri(),
                oidcCfg.getClientId(),
                oidcCfg.getJwkSetUri().isBlank() ? "(discovered)" : oidcCfg.getJwkSetUri(),
                authMethod.getValue());
    }

    // ── IdP user provisioning ────────────────────────────────────────────────────

    /**
     * Returns a success handler that auto-provisions IdP users and locks their email address on first login, then
     * delegates to the standard saved-request redirect. For local (static) auth the provisioning step is a no-op.
     */
    private AuthenticationSuccessHandler idpProvisioningSuccessHandler() {
        var delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/");
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
        if (!(userStore instanceof JdbcUserStore jdbc)) return;

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
            jdbc.upsertLockedEmail(username, email.strip().toLowerCase(), authSource);
            log.info("Locked {} email '{}' for user '{}'", authSource, email, username);
        } else {
            log.warn("IdP login for '{}' via {} returned no email address", username, authSource);
        }
    }

    /**
     * Loads an RSA private key from a PKCS#8 PEM file and wraps it in a Nimbus {@link RSAKey} for use with
     * {@link NimbusJwtClientAuthenticationParametersConverter}. The public key is derived from the CRT parameters
     * embedded in the private key — no separate public key file is needed.
     *
     * <p>Generate a suitable key pair with:
     *
     * <pre>
     * openssl genrsa -out private.pem 2048
     * openssl pkcs8 -topk8 -nocrypt -in private.pem -out private-pkcs8.pem
     * openssl rsa -in private.pem -pubout -out public.pem   # register this with your IDP
     * </pre>
     */
    private static RSAKey loadRsaKey(String pemPath) {
        try {
            String pem = Files.readString(Path.of(pemPath))
                    .replaceAll("-----[^-]+-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            var privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            var crtKey = (RSAPrivateCrtKey) privateKey;
            var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey)
                    .privateKey((RSAPrivateKey) privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
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
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .roles("USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
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
