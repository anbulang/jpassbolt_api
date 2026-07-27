package com.jpassbolt.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skeleton page is served from a SECOND, bare Tomcat context that has no
 * Spring Security filter chain (see {@link SkeletonPageConfig}), so MockMvc
 * cannot reach it and the servlet is driven directly here.
 *
 * <p>
 * Two things must hold: the official security header set is written by hand
 * (SP-37), and the unsafe-mode flag is decided on the SERVER (SP-36) — plain
 * http on a non-loopback host, and nothing else, lights the footer warning.
 * </p>
 */
class SkeletonPageServletTest {

    private static final String UNSAFE_ON = "var UNSAFE_MODE = ('true' === 'true');";
    private static final String UNSAFE_OFF = "var UNSAFE_MODE = ('false' === 'true');";

    private final SkeletonPageConfig.SkeletonPageServlet servlet = new SkeletonPageConfig.SkeletonPageServlet();

    private MockHttpServletRequest request(String uri, String host, boolean secure) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setServerName(host);
        request.setSecure(secure);
        request.setScheme(secure ? "https" : "http");
        return request;
    }

    private MockHttpServletResponse serve(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doGet(request, response);
        return response;
    }

    // ------------------------------------------------------------------
    // SP-37: security headers on the filter-less context
    // ------------------------------------------------------------------

    @Test
    void testSkeletonPage_CarriesOfficialSecurityHeaderSet() throws Exception {
        MockHttpServletResponse response = serve(request("/auth/login", "localhost", false));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(SecurityHeaders.X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff");
        assertThat(response.getHeader(SecurityHeaders.X_DOWNLOAD_OPTIONS)).isEqualTo("noopen");
        assertThat(response.getHeader(SecurityHeaders.X_PERMITTED_CROSS_DOMAIN_POLICIES)).isEqualTo("all");
        assertThat(response.getHeader(SecurityHeaders.REFERRER_POLICY)).isEqualTo("same-origin");
        assertThat(response.getHeader(SecurityHeaders.X_FRAME_OPTIONS)).isEqualTo("SAMEORIGIN");
    }

    /** The 302 ("/" -> /auth/login) and the 404 must carry them too. */
    @Test
    void testRedirectAndNotFound_AlsoCarrySecurityHeaders() throws Exception {
        MockHttpServletResponse redirect = serve(request("/", "localhost", false));
        assertThat(redirect.getStatus()).isEqualTo(302);
        assertThat(redirect.getHeader("Location")).isEqualTo("/auth/login?redirect=%2F");
        assertThat(redirect.getHeader(SecurityHeaders.REFERRER_POLICY)).isEqualTo("same-origin");
        assertThat(redirect.getHeader(SecurityHeaders.X_DOWNLOAD_OPTIONS)).isEqualTo("noopen");
        // The redirect/404 (no body) carry the strict CSP baseline.
        assertThat(redirect.getHeader(SecurityHeaders.CONTENT_SECURITY_POLICY))
                .isEqualTo(SecurityHeaders.CONTENT_SECURITY_POLICY_VALUE);

        MockHttpServletResponse notFound = serve(request("/favicon.ico", "localhost", false));
        assertThat(notFound.getStatus()).isEqualTo(404);
        assertThat(notFound.getHeader(SecurityHeaders.REFERRER_POLICY)).isEqualTo("same-origin");
        assertThat(notFound.getHeader(SecurityHeaders.X_FRAME_OPTIONS)).isEqualTo("SAMEORIGIN");
    }

    /**
     * The served HTML carries one inline bootstrap script, allowed by a
     * per-request CSP nonce that must appear in BOTH the header and the tag; the
     * policy also grants {@code img-src data:} for the embedded brand icon. A
     * fresh nonce is minted per response.
     */
    @Test
    void testSkeletonHtml_CspNonceMatchesInlineScriptTag() throws Exception {
        MockHttpServletResponse response = serve(request("/auth/login", "localhost", false));
        String csp = response.getHeader(SecurityHeaders.CONTENT_SECURITY_POLICY);
        String page = response.getContentAsString();

        assertThat(csp).contains("script-src 'self' 'nonce-");
        assertThat(csp).contains("img-src 'self' data:");

        // Extract the nonce from the header and assert the <script> tag carries it.
        int start = csp.indexOf("'nonce-") + "'nonce-".length();
        String nonce = csp.substring(start, csp.indexOf("'", start));
        assertThat(nonce).isNotBlank();
        assertThat(page).contains("<script nonce=\"" + nonce + "\">");
        assertThat(page).doesNotContain(SkeletonPageConfig.SkeletonPageServlet.CSP_NONCE_TOKEN);

        // A second request gets a different nonce (per-request, not static).
        String csp2 = serve(request("/auth/login", "localhost", false))
                .getHeader(SecurityHeaders.CONTENT_SECURITY_POLICY);
        assertThat(csp2).isNotEqualTo(csp);
    }

    // ------------------------------------------------------------------
    // SP-36: server-decided unsafe-mode banner
    // ------------------------------------------------------------------

    @Test
    void testPlainHttpOnPublicHost_EnablesUnsafeMode() throws Exception {
        MockHttpServletResponse response = serve(request("/app", "vault.example.com", false));

        assertThat(response.getContentAsString()).contains(UNSAFE_ON);
        // no shared cache may serve this body to another origin
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void testHttps_DisablesUnsafeMode() throws Exception {
        MockHttpServletResponse response = serve(request("/app", "vault.example.com", true));

        assertThat(response.getContentAsString()).contains(UNSAFE_OFF);
    }

    /** TLS-terminating reverse proxy forwarding plain http: the header decides. */
    @Test
    void testForwardedProtoHttps_DisablesUnsafeMode() throws Exception {
        MockHttpServletRequest request = request("/app", "vault.example.com", false);
        request.addHeader("X-Forwarded-Proto", "https");

        assertThat(serve(request).getContentAsString()).contains(UNSAFE_OFF);
    }

    /** A spoofed non-scheme token must not be honored — it falls back to the real scheme. */
    @Test
    void testForwardedProtoGarbage_KeepsUnsafeMode() throws Exception {
        MockHttpServletRequest request = request("/app", "vault.example.com", false);
        request.addHeader("X-Forwarded-Proto", "javascript:alert(1)");

        assertThat(serve(request).getContentAsString()).contains(UNSAFE_ON);
    }

    @Test
    void testLoopbackHosts_NeverShowUnsafeMode() throws Exception {
        for (String host : new String[] { "localhost", "127.0.0.1", "127.53.1.9", "::1", "app.localhost" }) {
            assertThat(serve(request("/app", host, false)).getContentAsString())
                    .as("loopback host %s must not warn", host)
                    .contains(UNSAFE_OFF);
        }
    }

    /**
     * "127.0.0.1.evil.example" is a real, attacker-registrable public domain —
     * a prefix match on "127." would silently suppress the warning on it.
     */
    @Test
    void testLoopbackLookalikeHost_StillShowsUnsafeMode() throws Exception {
        assertThat(serve(request("/app", "127.0.0.1.evil.example", false)).getContentAsString())
                .contains(UNSAFE_ON);
    }

    /** The banner text ships in both locales, and the raw file is valid JS (flag defaults off). */
    @Test
    void testBannerCopy_IsBilingual() throws Exception {
        String page = serve(request("/app", "vault.example.com", false)).getContentAsString();

        assertThat(page).contains("unsafeMode: '不安全模式");
        assertThat(page).contains("unsafeMode: 'Unsafe mode");
        assertThat(page).doesNotContain(SkeletonPageConfig.SkeletonPageServlet.UNSAFE_MODE_TOKEN);
    }

    @Test
    void testBrandIcon_IsEmbeddedInHeaderAndFavicon() throws Exception {
        String page = serve(request("/auth/login", "localhost", false)).getContentAsString();

        assertThat(page).contains("<link rel=\"icon\" type=\"image/png\" href=\"data:image/png;base64,");
        assertThat(page).contains("<img class=\"lg\" src=\"data:image/png;base64,");
        assertThat(page).doesNotContain(SkeletonPageConfig.SkeletonPageServlet.BRAND_ICON_TOKEN);
    }
}
