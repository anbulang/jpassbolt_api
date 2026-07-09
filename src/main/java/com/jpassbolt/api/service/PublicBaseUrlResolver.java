package com.jpassbolt.api.service;

import com.jpassbolt.api.config.SettingsProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the PUBLIC trusted-domain base URL (scheme://host[:port], no path,
 * no trailing slash) that browser-facing links (recovery / setup emails, the
 * skeleton page) must point at.
 *
 * <p>Passbolt's whole extension-parity model hinges on one invariant: the
 * skeleton page, the recovery link in the email, and the browser extension's
 * trusted-domain regex must all resolve to the <em>same origin the user is
 * actually browsing</em>. A hard-coded base URL (the old {@code
 * jpassbolt.app.base-url = http://localhost:5173}, the retired SPA) breaks that
 * — a recovery link opens the wrong origin and the extension never attaches.</p>
 *
 * <p><b>Security — Host header injection / password-reset poisoning.</b> The
 * request {@code Host} / {@code X-Forwarded-Host} headers are attacker
 * controllable. If we built recovery-email links from an unchecked host, an
 * attacker could request a recovery with a spoofed {@code Host: evil.example}
 * and the victim's email would carry {@code evil.example/setup/recover/start/…}
 * — clicking it hands the recovery token (account takeover) to the attacker.
 * So the request-derived origin is honored ONLY when the hostname is a
 * known-safe local dev host or explicitly allowlisted; otherwise we fall back
 * to the configured {@code full-base-url}.</p>
 *
 * <p>Resolution order:
 * <ol>
 *   <li>explicit override {@code jpassbolt.app.public-base-url} (blank by
 *       default) — the recommended production setting;</li>
 *   <li>the current request's own origin (honoring {@code X-Forwarded-Proto}/
 *       {@code X-Forwarded-Host}) — BUT only if {@link #isTrustedHost} accepts
 *       the hostname. Local dev (localhost / 127.* / ::1 / *.local /
 *       *.localhost) is auto-trusted so no config is needed on a dev box;
 *       production hosts must be added to {@code jpassbolt.app.trusted-hosts}
 *       (comma-separated) or set via the override above;</li>
 *   <li>last resort: {@code settings.full-base-url}.</li>
 * </ol>
 * The origin is the DOMAIN ROOT, not the {@code /api} context path: these links
 * target the skeleton context ({@code /setup/recover/start/…}).</p>
 */
@Component
@RequiredArgsConstructor
public class PublicBaseUrlResolver {

    private final SettingsProperties settingsProperties;

    @Value("${jpassbolt.app.public-base-url:}")
    private String override;

    /**
     * Extra hostnames (comma-separated, no scheme/port) whose request Host may
     * be trusted for building outbound browser links. Set this to the public
     * hostname(s) in production, or use {@code public-base-url} instead.
     */
    @Value("${jpassbolt.app.trusted-hosts:}")
    private String trustedHostsCsv;

    /** @return scheme://host[:port] with no trailing slash. */
    public String resolve() {
        if (override != null && !override.isBlank()) {
            return trimTrailingSlash(override.trim());
        }
        String fromRequest = fromCurrentRequest();
        if (fromRequest != null) {
            return fromRequest;
        }
        return trimTrailingSlash(settingsProperties.getFullBaseUrl());
    }

    /** Request-derived origin, or null when out of request scope or the host is untrusted. */
    private String fromCurrentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest req = attrs.getRequest();
        String scheme = firstNonBlank(req.getHeader("X-Forwarded-Proto"), req.getScheme());

        String hostname;
        String hostWithPort;
        String forwardedHost = req.getHeader("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            hostWithPort = forwardedHost.split(",")[0].trim();
            hostname = stripPort(hostWithPort);
        } else {
            hostname = req.getServerName();
            int port = req.getServerPort();
            boolean defaultPort = ("https".equals(scheme) && port == 443)
                    || ("http".equals(scheme) && port == 80);
            hostWithPort = (defaultPort || port <= 0) ? hostname : hostname + ":" + port;
        }

        // SECURITY GATE: never build links from an attacker-controllable host
        // unless it is a safe dev host or explicitly allowlisted.
        if (!isTrustedHost(hostname)) {
            return null;
        }
        return scheme + "://" + hostWithPort;
    }

    /**
     * A hostname we are willing to echo back into an outbound link. Local dev
     * hosts are inherently safe (no remote attacker can spoof the Host of a
     * request a developer sends to their own loopback / mDNS box); everything
     * else must be explicitly configured.
     */
    private boolean isTrustedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        // strip an IPv6 bracket form like [::1]
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (trustedHosts().contains(h)) {
            return true;
        }
        return h.equals("localhost")
                || h.equals("::1")
                || h.startsWith("127.")
                || h.endsWith(".local")
                || h.endsWith(".localhost");
    }

    private Set<String> trustedHosts() {
        Set<String> out = new HashSet<>();
        if (trustedHostsCsv != null && !trustedHostsCsv.isBlank()) {
            for (String part : trustedHostsCsv.split(",")) {
                String t = part.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String stripPort(String hostMaybePort) {
        if (hostMaybePort.startsWith("[")) {
            // [ipv6]:port — keep through the closing bracket.
            int close = hostMaybePort.indexOf(']');
            return close >= 0 ? hostMaybePort.substring(0, close + 1) : hostMaybePort;
        }
        int colon = hostMaybePort.indexOf(':');
        return colon >= 0 ? hostMaybePort.substring(0, colon) : hostMaybePort;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a.trim() : b;
    }

    private static String trimTrailingSlash(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }
}
