package com.jpassbolt.api.config;

import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.ActionLogService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Records one {@code action_logs} row per controller request — the JPassbolt analogue of
 * the PHP Log plugin's after-filter that builds an action log from the {@code UserAction}
 * singleton.
 *
 * <p>Runs at {@link #afterCompletion}, when the final HTTP status is known. The action
 * name is derived from the matched {@link HandlerMethod} as
 * {@code "<ControllerSimpleName>.<method>"} (e.g. {@code "ResourceController.getResource"}).
 * The actual write/blacklist/enable logic lives in {@link ActionLogService}; this class is
 * just the request-to-fields adapter and is wrapped so it can never disturb the response.</p>
 *
 * <p><b>Coverage limits (documented deviations from PHP):</b>
 * <ul>
 *   <li>Only requests that reach a {@code @Controller} handler are logged. Requests rejected
 *       earlier in the Spring Security filter chain (e.g. a 401 from
 *       {@code JwtAuthenticationFilter}, or a 302 from {@code MfaEnforcementFilter}) never
 *       reach a handler and are not logged. PHP logs those as {@code Error.error}.</li>
 *   <li>Logged once per logical request: the {@link DispatcherType#REQUEST} guard excludes
 *       internal {@code ERROR}/{@code FORWARD} re-dispatches.</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionLogInterceptor implements HandlerInterceptor {

    private final ActionLogService actionLogService;
    private final UserRepository userRepository;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // Log only on the primary request dispatch and only for controller methods.
            if (request.getDispatcherType() != DispatcherType.REQUEST
                    || !(handler instanceof HandlerMethod handlerMethod)) {
                return;
            }
            String actionName = handlerMethod.getBeanType().getSimpleName()
                    + "." + handlerMethod.getMethod().getName();
            String context = request.getMethod() + " " + requestPath(request);
            actionLogService.logAction(currentUserId(), actionName, context, response.getStatus());
        } catch (Exception e) {
            // The interceptor must never affect the (already committed) response.
            log.error("Action log interceptor failed: {}", e.getMessage());
        }
    }

    /** Request path without the {@code /api} context path (matches PHP's context, e.g. "/resources.json"). */
    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }

    /** The authenticated user id, or {@code null} for anonymous/guest requests. */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String username = auth.getName();
        if (username == null) {
            return null;
        }
        return userRepository.findByUsername(username).map(User::getId).orElse(null);
    }
}
