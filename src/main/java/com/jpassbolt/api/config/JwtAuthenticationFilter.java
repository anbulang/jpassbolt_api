package com.jpassbolt.api.config;

import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT authentication filter that validates Bearer tokens on every request.
 * Extracts the JWT from the Authorization header, validates the RS256
 * signature and expiry, then resolves the subject claim (the user UUID —
 * matching the PHP JWT plugin where {@code sub} is the user id) to a user
 * record. The Spring Security principal is set to the user's username so
 * the project-wide {@code getCurrentUserId()} convention in the controllers
 * (lookup by username) keeps working unchanged.
 *
 * <p>
 * Modeled after the PHP {@code JwtAuthenticationService} middleware.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // Skip if no Bearer token present
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        try {
            // sub = user UUID; signature is verified during extraction (RS256)
            final String userId = jwtService.extractSubject(jwt);

            // Only authenticate if not already authenticated
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null
                    && jwtService.isTokenValid(jwt)) {
                // Resolve the user: must exist, be active, not deleted, not
                // disabled (PHP plugin re-checks the user on every request).
                userRepository.findById(userId)
                        .filter(u -> !u.getDeleted() && u.getActive() && u.getDisabled() == null)
                        .ifPresent(user -> {
                            UserDetails userDetails = new User(user.getUsername(), "", Collections.emptyList());
                            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                            authToken.setDetails(
                                    new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authToken);
                            log.debug("JWT authenticated user: {}", user.getUsername());
                        });
            }
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            // Don't set authentication — Spring Security will return 401/403
        }

        filterChain.doFilter(request, response);
    }
}
