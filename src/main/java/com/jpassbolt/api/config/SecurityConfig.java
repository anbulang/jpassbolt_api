package com.jpassbolt.api.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
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
                                                .requestMatchers("/auth/**",
                                                                "/healthcheck/status", "/healthcheck/status.json",
                                                                "/settings", "/settings.json",
                                                                "/avatars/view/**",
                                                                "/setup/**")
                                                .permitAll()
                                                .anyRequest().authenticated())
                                .addFilterBefore(jwtAuthenticationFilter,
                                                UsernamePasswordAuthenticationFilter.class);
                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(List.of(
                                "http://localhost:5173",
                                "http://localhost:5174",
                                "http://localhost:3000"));
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
