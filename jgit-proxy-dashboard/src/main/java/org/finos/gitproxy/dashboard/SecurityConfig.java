package org.finos.gitproxy.dashboard;

import lombok.extern.slf4j.Slf4j;
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

/**
 * Spring Security configuration for the dashboard module. Protects {@code /api/**}, {@code /login}, and
 * {@code /logout}. Git paths ({@code /push/**}, {@code /proxy/**}) are explicitly excluded so git clients are never
 * redirected to a login page.
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        String apiKey = System.getenv("GITPROXY_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("API key authentication enabled (GITPROXY_API_KEY is set)");
            http.addFilterBefore(new ApiKeyAuthFilter(apiKey), UsernamePasswordAuthenticationFilter.class);
        } else {
            log.info("API key authentication disabled (GITPROXY_API_KEY not set)");
        }

        http.securityMatcher("/api/**", "/login", "/logout")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login.html?logout").permitAll())
                .csrf(csrf -> csrf.disable())
                // API calls from the SPA must get 401, not a redirect to /login.
                // Redirects cause fetch() to follow to the HTML login page and then
                // JSON.parse blows up on the HTML response.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        (req, res, e) -> res.sendError(401),
                        req -> req.getServletPath().startsWith("/api/")));
        return http.build();
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
