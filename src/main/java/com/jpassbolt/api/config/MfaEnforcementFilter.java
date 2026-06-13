package com.jpassbolt.api.config;

import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.MfaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * MFA enforcement gate, ported from the PHP
 * {@code MfaRequiredCheckMiddleware}: every authenticated request outside a
 * small whitelist is answered with a 302 to
 * {@code {fullBaseUrl}/mfa/verify/error.json} while the user still has an
 * MFA verification pending (org and account both have a ready provider, and
 * no valid passbolt_mfa cookie accompanies the request).
 *
 * <p>
 * Faithful to PHP: even JSON/API requests get the 302 (the official client
 * follows the redirect and receives the 403 + mfa_providers envelope from
 * the error endpoint). No error envelope is written here — the
 * {@code @ControllerAdvice} does not cover the filter layer, only a status
 * and a Location header are set.
 * </p>
 *
 * <p>
 * Whitelist (servlet paths, without the /api context-path): /auth/,
 * /mfa/verify, /logout. Note /mfa/setup is deliberately NOT whitelisted —
 * a user with a configured provider must verify before re-entering setup,
 * same as PHP.
 * </p>
 *
 * <p>
 * Registration: this is a {@code @Component} filter, so Spring Boot
 * auto-registers it in the servlet filter chain AFTER the security filter
 * chain (default order), where the JWT-authenticated SecurityContext is
 * already populated. Adding it to the Spring Security chain via
 * {@code addFilterAfter(..., JwtAuthenticationFilter.class)} (blueprint
 * integration request) is compatible: OncePerRequestFilter guarantees a
 * single execution per request either way.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MfaEnforcementFilter extends OncePerRequestFilter {

    private static final String MFA_COOKIE = "passbolt_mfa";

    /** PHP MfaRequiredCheckMiddleware whitelisted path prefixes. */
    private static final String[] WHITELIST_PREFIXES = { "/auth/", "/mfa/verify", "/logout" };

    private final MfaService mfaService;
    private final UserRepository userRepository;
    private final SettingsProperties settingsProperties;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken
                || auth.getName() == null) {
            // Unauthenticated/anonymous requests are someone else's problem.
            filterChain.doFilter(request, response);
            return;
        }

        String path = requestPath(request);
        for (String prefix : WHITELIST_PREFIXES) {
            if (path.startsWith(prefix)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        Optional<User> user = userRepository.findByUsername(auth.getName());
        if (user.isEmpty()) {
            // Unknown principal: let the controller layer produce its 404.
            filterChain.doFilter(request, response);
            return;
        }

        String mfaCookie = readMfaCookie(request);
        if (mfaService.isMfaCheckRequired(user.get().getId(), mfaCookie)) {
            log.debug("MFA verification pending for {} — redirecting {} to error endpoint",
                    auth.getName(), path);
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader(HttpHeaders.LOCATION,
                    settingsProperties.getFullBaseUrl() + "/mfa/verify/error.json");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The request path inside the application (request URI minus the /api
     * context-path), matching how SecurityConfig matchers are written.
     */
    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private String readMfaCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (MFA_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
