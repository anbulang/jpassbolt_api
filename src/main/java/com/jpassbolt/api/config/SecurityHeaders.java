package com.jpassbolt.api.config;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The exact security-header set official Passbolt sends, in one place.
 *
 * <p>
 * Source of truth: {@code passbolt_api_ref/src/Application.php} enables
 * CakePHP's {@code SecurityHeadersMiddleware} whenever
 * {@code passbolt.security.setHeaders} is true (default {@code true}, see
 * {@code config/default.php}) and configures it with
 * {@code setCrossDomainPolicy()->setReferrerPolicy()->setXFrameOptions()->noOpen()->noSniff()}
 * — all five called with NO argument, i.e. the CakePHP 5.x defaults:
 * </p>
 *
 * <pre>
 *   x-permitted-cross-domain-policies: all        (setCrossDomainPolicy, default self::ALL)
 *   referrer-policy:                   same-origin (setReferrerPolicy,   default self::SAME_ORIGIN)
 *   x-frame-options:                   sameorigin  (setXFrameOptions,    default self::SAMEORIGIN)
 *   x-download-options:                noopen      (noOpen)
 *   x-content-type-options:            nosniff     (noSniff)
 * </pre>
 *
 * <p>
 * Spring Security only emits {@code X-Content-Type-Options} and
 * {@code X-Frame-Options: DENY} out of the box, so {@link SecurityConfig} adds
 * the other three and relaxes frame-options to {@code SAMEORIGIN}. Values are
 * mirrored here as constants because the skeleton page servlet
 * ({@link SkeletonPageConfig}) runs in a second, filter-less Tomcat context and
 * must send the same set by hand.
 * </p>
 *
 * <p>
 * <b>On {@code X-Permitted-Cross-Domain-Policies: all}.</b> {@code all} is
 * permissive, and {@code none} would be stricter — but it is what official
 * Passbolt sends, and it is inert here: the header only tells legacy Adobe
 * Flash/Acrobat clients whether to honor a {@code /crossdomain.xml} policy
 * file, and neither this application nor the skeleton context serves one. Kept
 * at the official value for parity; an operator wanting {@code none} can
 * override it at the reverse proxy (see {@code docs/deployment.md}).
 * </p>
 *
 * <p>
 * <b>On {@code X-Frame-Options: SAMEORIGIN}.</b> This is a relaxation of
 * Spring's {@code DENY} and cannot break — nor is it needed by — the browser
 * extension's takeover. {@code X-Frame-Options} constrains who may frame
 * <em>this</em> response; the extension does the opposite, appending an iframe
 * whose {@code src} is {@code chrome.runtime.getURL('app.html')} INTO the
 * skeleton page (see {@code jpassbolt_browser_extension/extension/src/content/appBootstrap.ts}
 * {@code mount()}). That child document is served by the extension from a
 * {@code chrome-extension://} origin and never carries our headers.
 * {@code SAMEORIGIN} is chosen purely to match official Passbolt.
 * </p>
 */
public final class SecurityHeaders {

    public static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String X_CONTENT_TYPE_OPTIONS_VALUE = "nosniff";

    public static final String X_DOWNLOAD_OPTIONS = "X-Download-Options";
    public static final String X_DOWNLOAD_OPTIONS_VALUE = "noopen";

    public static final String X_PERMITTED_CROSS_DOMAIN_POLICIES = "X-Permitted-Cross-Domain-Policies";
    public static final String X_PERMITTED_CROSS_DOMAIN_POLICIES_VALUE = "all";

    public static final String REFERRER_POLICY = "Referrer-Policy";
    /** Must stay equal to {@code ReferrerPolicy.SAME_ORIGIN.getPolicy()}. */
    public static final String REFERRER_POLICY_VALUE = "same-origin";

    public static final String X_FRAME_OPTIONS = "X-Frame-Options";
    /** Upper case: the token is case-insensitive, this is what Spring Security writes. */
    public static final String X_FRAME_OPTIONS_VALUE = "SAMEORIGIN";

    public static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    /**
     * The official Passbolt default CSP, verbatim from
     * {@code passbolt_api_ref/src/Middleware/ContentSecurityPolicyMiddleware.php}
     * (the {@code $defaultCsp} array joined with "; "). This is what the browser
     * extension is built against, so mirroring it exactly is the safest choice —
     * note {@code frame-src} does NOT list {@code chrome-extension:} yet the
     * extension's injected {@code app.html} iframe still loads, because Chrome
     * exempts extension-injected frames from the embedding page's CSP.
     *
     * <p>Applied as-is to every API ({@code /api}) response: JSON carries no
     * inline script/style/image, so the strict {@code 'self'} directives are
     * free of cost. The self-contained skeleton page needs two relaxations —
     * see {@link #skeletonContentSecurityPolicy(String)}.</p>
     */
    public static final String CONTENT_SECURITY_POLICY_VALUE =
            "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self'; "
            + "frame-src 'self' https://*.duosecurity.com; "
            + "frame-ancestors 'none'; "
            + "form-action 'self' https://*.duosecurity.com";

    /**
     * CSP for the self-contained skeleton page. It differs from the API policy
     * ({@link #CONTENT_SECURITY_POLICY_VALUE}) in exactly two directives:
     * <ul>
     *   <li>{@code script-src} gains a per-request {@code 'nonce-...'} so the
     *       page's single inline bootstrap {@code <script>} runs while arbitrary
     *       injected inline script stays blocked (the shell embeds its whole JS
     *       inline by design — official Passbolt serves it as external files);</li>
     *   <li>{@code img-src} gains {@code data:} for the brand icon, which the
     *       shell embeds as a {@code data:image/png} URI rather than an asset
     *       route.</li>
     * </ul>
     * Everything else (same-origin {@code fetch} to {@code /api}, inline
     * {@code <style>}) is covered by {@code default-src}/{@code style-src}.
     *
     * @param scriptNonce a fresh, unpredictable per-request nonce (base64)
     */
    public static String skeletonContentSecurityPolicy(String scriptNonce) {
        return "default-src 'self'; "
                + "script-src 'self' 'nonce-" + scriptNonce + "'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "frame-src 'self' https://*.duosecurity.com; "
                + "frame-ancestors 'none'; "
                + "form-action 'self' https://*.duosecurity.com";
    }

    private SecurityHeaders() {
    }

    /**
     * Write the whole set onto a response that does NOT pass through Spring
     * Security's {@code HeaderWriterFilter} — today only the skeleton page.
     */
    public static void applyTo(HttpServletResponse response) {
        response.setHeader(X_CONTENT_TYPE_OPTIONS, X_CONTENT_TYPE_OPTIONS_VALUE);
        response.setHeader(X_DOWNLOAD_OPTIONS, X_DOWNLOAD_OPTIONS_VALUE);
        response.setHeader(X_PERMITTED_CROSS_DOMAIN_POLICIES, X_PERMITTED_CROSS_DOMAIN_POLICIES_VALUE);
        response.setHeader(REFERRER_POLICY, REFERRER_POLICY_VALUE);
        response.setHeader(X_FRAME_OPTIONS, X_FRAME_OPTIONS_VALUE);
    }
}
