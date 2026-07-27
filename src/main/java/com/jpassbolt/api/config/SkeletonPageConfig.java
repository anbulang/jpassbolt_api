package com.jpassbolt.api.config;

import com.jpassbolt.api.util.HttpRequestSecurity;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Serves the official-Passbolt-shaped skeleton page at the DOMAIN ROOT
 * ({@code /} and {@code /app*}) while the whole Spring application stays under
 * its {@code /api} context path.
 *
 * Passbolt's web surface is just this: the server renders a bootstrap shell
 * and the browser extension's content script replaces it with an
 * extension-origin iframe hosting the real vault UI. The shell must live at
 * the domain root (the extension's trusted-domain regex is
 * {@code ^{domain}/?(/app.*)?$}), which the {@code /api} servlet context can
 * never serve — so a SECOND, bare Tomcat context is mounted at {@code ""}.
 * Tomcat's mapper routes {@code /api/*} to the Spring context and everything
 * else here, keeping the API, its security filter chain and every MockMvc /
 * contract test completely untouched. The skeleton context intentionally has
 * no Spring filters: the page is public static HTML by design.
 *
 * Because there is no {@code HeaderWriterFilter} here, the servlet writes the
 * official Passbolt security headers itself (see {@link SecurityHeaders}), and
 * because the page must warn the user when it is being served over plain http
 * on a non-loopback host, the HTML carries a server-substituted flag (see
 * {@link SkeletonPageServlet#UNSAFE_MODE_TOKEN}). The canonical brand icon is
 * likewise embedded from a classpath PNG as a data URI so the standalone root
 * context does not need an extra public asset route.
 */
@Configuration
public class SkeletonPageConfig {

    @Bean
    public TomcatServletWebServerFactory skeletonAwareTomcatFactory() {
        return new TomcatServletWebServerFactory() {
            @Override
            protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
                // Only mount the skeleton when the Spring context is NOT at the
                // root itself (e.g. tests without server.servlet.context-path):
                // two contexts named "" would collide.
                if (!getContextPath().isEmpty()) {
                    Context root = tomcat.addContext("", docBase());
                    Tomcat.addServlet(root, "skeleton", new SkeletonPageServlet());
                    root.addServletMappingDecoded("/", "skeleton");
                }
                return super.getTomcatWebServer(tomcat);
            }
        };
    }

    /**
     * Tomcat requires a docBase directory for the context, but the skeleton
     * context never reads a file from it (the page is served by a manually
     * registered servlet from a classpath resource). Point it at the existing
     * system temp directory rather than creating a fresh one per startup —
     * {@code Files.createTempDirectory} left a new empty dir behind on every
     * restart / test run (never cleaned up). Tomcat only requires the directory
     * to exist, not to be writable or exclusive.
     */
    private static String docBase() {
        return System.getProperty("java.io.tmpdir");
    }

    static final class SkeletonPageServlet extends HttpServlet {

        /**
         * The request-dependent template variable of the page. {@code app.html} contains
         * {@code var UNSAFE_MODE = ('__JP_UNSAFE_MODE__' === 'true');} — the
         * quoted form keeps the file valid JavaScript when it is opened
         * directly (un-substituted it just evaluates to {@code false}), and the
         * server only ever substitutes the literals {@code true} / {@code false},
         * so no injection surface is opened.
         */
        static final String UNSAFE_MODE_TOKEN = "__JP_UNSAFE_MODE__";
        static final String BRAND_ICON_TOKEN = "__JP_BRAND_ICON__";
        /** Replaced per request with the CSP script nonce (see {@code app.html} {@code <script nonce>}). */
        static final String CSP_NONCE_TOKEN = "__JP_CSP_NONCE__";

        private static final SecureRandom NONCE_RNG = new SecureRandom();

        /** A fresh, unpredictable per-request base64 nonce for the inline bootstrap script. */
        private static String newCspNonce() {
            byte[] bytes = new byte[16];
            NONCE_RNG.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        private final String html = loadSkeleton();
        private final String brandIconDataUri = loadBrandIconDataUri();

        private static String loadSkeleton() {
            try (InputStream in = Objects.requireNonNull(
                    SkeletonPageServlet.class.getResourceAsStream("/skeleton/app.html"),
                    "missing classpath resource /skeleton/app.html")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String loadBrandIconDataUri() {
            try (InputStream in = Objects.requireNonNull(
                    SkeletonPageServlet.class.getResourceAsStream("/skeleton/icon-128.png"),
                    "missing classpath resource /skeleton/icon-128.png")) {
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * "Unsafe mode" = this page reached the browser over plain http on a
         * host that is NOT the user's own machine, i.e. every byte (including
         * the JWT the extension will later send) is readable on the wire.
         * Official Passbolt shows the same footer warning when HTTPS is off.
         *
         * <p>
         * Loopback is excluded so local development never shows the banner. No
         * application-layer https redirect is performed — TLS termination is the
         * reverse proxy's job (see {@code docs/deployment.md}); a redirect here
         * would break plain-http local development for no gain.
         * </p>
         *
         * <p>
         * Note the corollary for operators: a proxy that terminates TLS and
         * forwards plain http MUST send {@code X-Forwarded-Proto: https}, or
         * this banner will (correctly, from the app's point of view) appear on
         * an https site.
         * </p>
         */
        static boolean isUnsafeMode(HttpServletRequest request) {
            return !HttpRequestSecurity.isSecureRequest(request)
                    && !HttpRequestSecurity.isLoopbackHost(HttpRequestSecurity.hostname(request));
        }

        // Browser-facing routes the extension content script attaches to
        // (content/appBootstrap.ts): the app shell (/ , /app…), the guest flows
        // reached from an email link (/setup/… , /recover…), and the sign-in
        // triage (/auth/login). All are served the SAME skeleton — the page's
        // own JS + the extension decide which state to paint. Everything else
        // (favicons, stray paths) still 404s.
        private static boolean isBrowserPageUrl(String path) {
            return "/".equals(path)
                    || "/app".equals(path) || path.startsWith("/app/")
                    || "/auth/login".equals(path)
                    || path.startsWith("/setup/")
                    || "/recover".equals(path) || path.startsWith("/recover/");
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            // Written first so they also ride on the 302 and the 404 — this
            // context has no Spring Security HeaderWriterFilter to do it. The
            // strict CSP is the baseline for the redirect/404 (no body); the 200
            // HTML branch overrides it below with a nonce-bearing variant.
            SecurityHeaders.applyTo(resp);
            resp.setHeader(SecurityHeaders.CONTENT_SECURITY_POLICY, SecurityHeaders.CONTENT_SECURITY_POLICY_VALUE);

            String path = req.getRequestURI();
            if (!isBrowserPageUrl(path)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            // Official Passbolt 302-redirects an unauthenticated browser hitting the
            // domain root to the login page (Application.php unauthenticatedRedirect →
            // /auth/login). This filter-less skeleton context can't read the session,
            // so we mirror the common (unauthenticated) case: "/" → /auth/login. The
            // extension still takes over /auth/login exactly as it would have at "/".
            if ("/".equals(path)) {
                resp.setStatus(HttpServletResponse.SC_FOUND);
                resp.setHeader("Location", "/auth/login?redirect=%2F");
                return;
            }
            // The one inline bootstrap <script> is allowed by a per-request nonce
            // carried in both the CSP header and the tag, so the strict policy can
            // block any other inline script. img-src also gains data: for the
            // embedded brand icon (skeletonContentSecurityPolicy).
            String cspNonce = newCspNonce();
            resp.setHeader(SecurityHeaders.CONTENT_SECURITY_POLICY,
                    SecurityHeaders.skeletonContentSecurityPolicy(cspNonce));

            byte[] page = html
                    .replace(UNSAFE_MODE_TOKEN, Boolean.toString(isUnsafeMode(req)))
                    .replace(BRAND_ICON_TOKEN, brandIconDataUri)
                    .replace(CSP_NONCE_TOKEN, cspNonce)
                    .getBytes(StandardCharsets.UTF_8);

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/html;charset=UTF-8");
            // The body now varies with the request's scheme and host, so it must
            // not be reused from a shared cache for a different origin.
            resp.setHeader("Cache-Control", "no-store");
            resp.setContentLength(page.length);
            resp.getOutputStream().write(page);
        }
    }
}
