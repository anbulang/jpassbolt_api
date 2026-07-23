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
