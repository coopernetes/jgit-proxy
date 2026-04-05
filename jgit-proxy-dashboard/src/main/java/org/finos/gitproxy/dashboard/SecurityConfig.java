package org.finos.gitproxy.dashboard;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.jetty.config.GitProxyConfig;
import org.finos.gitproxy.user.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration for the dashboard module. Protects {@code /api/**}, {@code /login}, and
 * {@code /logout}. Git paths ({@code /push/**}, {@code /proxy/**}) are explicitly excluded so git clients are never
 * redirected to a login page.
 *
 * <p>CSRF protection uses {@link CookieCsrfTokenRepository} so the SPA can read the {@code XSRF-TOKEN} cookie and send
 * it back as {@code X-XSRF-TOKEN} on mutating requests. The {@code /login} POST is exempt from CSRF (the static login
 * form cannot embed a token), which is acceptable because login-CSRF has a much lower impact than action-CSRF.
 *
 * <p>CORS is configured from {@code server.allowed-origins} in {@code git-proxy.yml}. When the list is empty (default)
 * only same-origin requests are accepted. Needed when the frontend is served from a different hostname (e.g. a load
 * balancer with a custom domain).
 *
 * <p>When no users are configured in {@code git-proxy.yml}, the {@link UserStore} is empty and all API requests are
 * rejected with 401. Configure at least one user to enable dashboard access.
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
        String apiKey = System.getenv("GITPROXY_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("API key authentication enabled (GITPROXY_API_KEY is set)");
            http.addFilterBefore(new ApiKeyAuthFilter(apiKey), UsernamePasswordAuthenticationFilter.class);
        } else {
            log.info("API key authentication disabled (GITPROXY_API_KEY not set)");
        }

        List<String> allowedOrigins = gitProxyConfig.getServer().getAllowedOrigins();
        if (!allowedOrigins.isEmpty()) {
            log.info("CORS enabled for origins: {}", allowedOrigins);
            http.cors(cors -> cors.configurationSource(corsConfigurationSource(allowedOrigins)));
        } else {
            log.debug("CORS not configured — same-origin only");
        }

        http.securityMatcher("/api/**", "/login", "/logout")
                .authorizeHttpRequests(auth -> auth
                        // Runtime config is public — the SPA fetches it before login to learn about the deployment
                        .requestMatchers("/api/runtime-config")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .formLogin(form -> form.loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login.html?logout").permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // The static login form cannot embed a CSRF token; login-CSRF risk is low
                        // (attacker can log you into their account but cannot access your data).
                        .ignoringRequestMatchers("/login")
                        // Requests using a custom X-Api-Key header are not vulnerable to CSRF — browsers
                        // cannot send custom headers cross-origin without a CORS preflight.
                        .ignoringRequestMatchers(req -> req.getHeader("X-Api-Key") != null))
                // API calls from the SPA must get 401, not a redirect to /login.
                // Redirects cause fetch() to follow to the HTML login page and then
                // JSON.parse blows up on the HTML response.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        (req, res, e) -> res.sendError(401),
                        req -> req.getServletPath().startsWith("/api/")));
        return http.build();
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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userStore
                .findByUsername(username)
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .roles("USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}
