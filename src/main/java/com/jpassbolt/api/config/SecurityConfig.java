package com.jpassbolt.api.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration.
 * <ul>
 * <li>CSRF disabled (stateless API)</li>
 * <li>JWT Bearer authentication via {@link JwtAuthenticationFilter}</li>
 * <li>CORS enabled for frontend dev server</li>
 * <li>Stateless session management</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

        private final JwtAuthenticationFilter jwtAuthenticationFilter;
        private final MfaEnforcementFilter mfaEnforcementFilter;

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                http
                                .csrf(AbstractHttpConfigurer::disable)
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> auth
                                                // Servlet-internal paths (context-path /api not included).
                                                // - /healthcheck/status*: public liveness probe (old /health-check removed with controller rewrite)
                                                // - /settings*: anonymous callers get the reduced guest view
                                                // - /avatars/view/** ONLY (not /avatars/**): <img src> loads carry no JWT
                                                // - /.well-known/jwks.json: anonymous alias of /auth/jwt/jwks.json
                                                //   (PHP top-level route redirects to the jwks action)
                                                .requestMatchers("/auth/**",
                                                                "/healthcheck/status", "/healthcheck/status.json",
                                                                "/settings", "/settings.json",
                                                                "/avatars/view/**",
                                                                "/setup/**",
                                                                // Guest-only account-recovery request
                                                                // (POST /users/recover.json). The
                                                                // /setup/recover/** start|complete|abort
                                                                // endpoints are already covered by
                                                                // /setup/** above.
                                                                "/users/recover", "/users/recover.json",
                                                                "/.well-known/jwks.json")
                                                .permitAll()
                                                .anyRequest().authenticated())
                                // Unauthenticated access to a protected endpoint must return
                                // 401 (not Spring's default 403). A 403 is reserved for an
                                // AUTHENTICATED caller who lacks permission (controller-thrown,
                                // enveloped). This entry point fires only AFTER authorization
                                // denies an anonymous request, so permitAll paths (e.g.
                                // /auth/login.json carrying a stale Bearer) are untouched, and
                                // the SPA's interceptor cleanly recovers an expired/invalid
                                // (e.g. ephemeral-key-rotated) token instead of looping on 403.
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint(
                                                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                                .addFilterBefore(jwtAuthenticationFilter,
                                                UsernamePasswordAuthenticationFilter.class)
                                // MFA gate runs after the JWT principal is in place
                                // (deterministic ordering inside the security chain;
                                // OncePerRequestFilter prevents a double run via the
                                // auto-registered servlet-chain instance).
                                .addFilterAfter(mfaEnforcementFilter, JwtAuthenticationFilter.class);
                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration config = new CorsConfiguration();
                // Exact origins for the first-party web SPA (dev ports only).
                config.setAllowedOrigins(List.of(
                                "http://localhost:5173",
                                "http://localhost:5174",
                                "http://localhost:3000"));
                // The Passbolt-compatible browser extension sends Origin:
                // chrome-extension://<id>. A pattern is needed because an unpacked /
                // self-built extension gets a per-install id, and allowCredentials(true)
                // forbids a bare "*". Without this the CORS filter 403s the extension's
                // GpgAuth request before the controller runs (the X-GPGAuth-* headers never
                // reach the client), breaking both this extension and the official one.
                //
                // Why this wildcard is acceptable here: auth is JWT *Bearer* with NO
                // cookies, so reflecting a credentialed origin leaks nothing to a foreign
                // extension (it holds no token); web origins (e.g. https://evil.com) still
                // do NOT match and stay blocked; and CORS cannot gate a malicious extension
                // regardless (host-permission SW fetches bypass client-side CORS).
                // HARDENING TODO before production: ship a fixed manifest `key` for the
                // extension and pin its published id(s) here instead of the wildcard.
                config.setAllowedOriginPatterns(List.of(
                                "chrome-extension://*",
                                "moz-extension://*"));
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                config.setAllowedHeaders(List.of("*"));
                config.setExposedHeaders(List.of(
                                "Authorization",
                                "X-GPGAuth-Authenticated",
                                "X-GPGAuth-Progress",
                                "X-GPGAuth-User-Auth-Token",
                                "X-GPGAuth-Verify-Response",
                                "X-GPGAuth-Error",
                                "X-GPGAuth-Debug",
                                "X-GPGAuth-Refer"));
                config.setAllowCredentials(true);
                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", config);
                return source;
        }
}
