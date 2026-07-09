package com.jpassbolt.api.config;

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

        private final byte[] html = loadSkeleton();

        private static byte[] loadSkeleton() {
            try (InputStream in = Objects.requireNonNull(
                    SkeletonPageServlet.class.getResourceAsStream("/skeleton/app.html"),
                    "missing classpath resource /skeleton/app.html")) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
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
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/html;charset=UTF-8");
            resp.setContentLength(html.length);
            resp.getOutputStream().write(html);
        }
    }
}
