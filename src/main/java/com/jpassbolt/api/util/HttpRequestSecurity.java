package com.jpassbolt.api.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * Transport-security facts about the current request: is it really reaching us
 * over TLS, and is the client talking to a loopback address?
 *
 * <p>
 * Both questions have to be answered identically in three places that cannot
 * share a bean — {@code PublicBaseUrlResolver} (outbound email links),
 * {@code MfaController} (the {@code Secure} flag of {@code passbolt_mfa}) and
 * the filter-less skeleton servlet ({@code SkeletonPageConfig}, which lives in
 * a second Tomcat context with no Spring wiring). Hence a static, dependency
 * free helper rather than a component.
 * </p>
 *
 * <p>
 * <b>Security — {@code X-Forwarded-*} are attacker controllable.</b> Nothing
 * upstream validates them, so:
 * </p>
 * <ul>
 * <li>{@link #safeScheme} accepts ONLY a clean {@code http}/{@code https}
 * token (the first value of a possibly comma-joined multi-proxy header). A
 * spoofed {@code "javascript:…"} or a malformed {@code "https, http"} falls
 * back to the servlet's own scheme — this is what stops a {@code javascript:}
 * scheme from ever reaching an email {@code href}.</li>
 * <li>{@link #isSecureRequest} ORs the servlet's own {@code isSecure()} in, so
 * a proxy that forwards a genuine TLS connection while stripping (or lying in)
 * {@code X-Forwarded-Proto} can never downgrade us to "not secure". The
 * reverse — an attacker asserting {@code X-Forwarded-Proto: https} over plain
 * http — only makes us mark a cookie {@code Secure}, i.e. the browser refuses
 * to store/send it. That is a self-inflicted denial of service for the
 * attacker, not a credential leak.</li>
 * </ul>
 */
public final class HttpRequestSecurity {

    private HttpRequestSecurity() {
    }

    /**
     * The scheme the CLIENT used, honoring a well-formed
     * {@code X-Forwarded-Proto}.
     *
     * @return exactly {@code "http"} or {@code "https"}, never anything else
     */
    public static String safeScheme(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isBlank()) {
            String first = forwardedProto.split(",")[0].trim().toLowerCase(Locale.ROOT);
            if ("http".equals(first) || "https".equals(first)) {
                return first;
            }
        }
        return "https".equalsIgnoreCase(request.getScheme()) ? "https" : "http";
    }

    /**
     * True when the client-to-edge hop is TLS. Use this for the {@code Secure}
     * cookie attribute (PHP {@code AbstractSecureCookieService::isSslOrCookiesSecure}
     * asks the same question via {@code $request->is('ssl')}).
     */
    public static boolean isSecureRequest(HttpServletRequest request) {
        return request.isSecure() || "https".equals(safeScheme(request));
    }

    /**
     * The hostname the client addressed (no port), honoring the first value of
     * {@code X-Forwarded-Host}. May still be bracketed for an IPv6 literal —
     * feed it to {@link #isLoopbackHost} / {@link #normalizeHost} rather than
     * comparing it raw.
     */
    public static String hostname(HttpServletRequest request) {
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            return stripPort(forwardedHost.split(",")[0].trim());
        }
        return request.getServerName();
    }

    /** Drop a trailing {@code :port}, keeping an IPv6 literal's brackets intact. */
    public static String stripPort(String hostMaybePort) {
        if (hostMaybePort == null) {
            return "";
        }
        if (hostMaybePort.startsWith("[")) {
            // [ipv6]:port — keep through the closing bracket.
            int close = hostMaybePort.indexOf(']');
            return close >= 0 ? hostMaybePort.substring(0, close + 1) : hostMaybePort;
        }
        int colon = hostMaybePort.indexOf(':');
        return colon >= 0 ? hostMaybePort.substring(0, colon) : hostMaybePort;
    }

    /** Lowercase and unwrap an IPv6 literal's {@code []}. */
    public static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }

    /**
     * True only for hosts the client's own browser resolves to ITS OWN machine.
     * Two callers depend on that exact meaning: a link built from such a host
     * can never reach a remote attacker (recovery-link poisoning), and a plain
     * http page served on such a host is not actually exposed on the wire (so
     * no unsafe-mode warning).
     *
     * <p>
     * Deliberately NOT {@code startsWith("127.")}: that would accept the
     * attacker-registrable, publicly resolvable domain
     * {@code 127.0.0.1.evil.example}. Only a strict dotted-quad 127.0.0.0/8
     * literal counts. {@code .local} (mDNS / RFC 6762) is also excluded — it
     * can resolve to an arbitrary host on the LAN.
     * </p>
     */
    public static boolean isLoopbackHost(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return false;
        }
        return h.equals("localhost")
                || h.equals("::1")
                || isLoopbackIpv4Literal(h)
                // RFC 6761 forces browsers to resolve *.localhost to loopback.
                || h.endsWith(".localhost");
    }

    /** True iff {@code h} is a dotted-quad 127.0.0.0/8 literal, e.g. 127.0.0.1. */
    private static boolean isLoopbackIpv4Literal(String h) {
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4 || !"127".equals(parts[0])) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') {   // ASCII-only: reject Unicode digits
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }
}
