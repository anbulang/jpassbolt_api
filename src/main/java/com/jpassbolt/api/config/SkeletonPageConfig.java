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
import java.nio.file.Files;
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

    /** Tomcat requires a docBase directory even for a purely programmatic context. */
    private static String docBase() {
        try {
            return Files.createTempDirectory("jpassbolt-skeleton").toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String path = req.getRequestURI();
            // Same shape as passbolt's ParseAppUrlService: root or /app…, nothing else.
            boolean isAppUrl = "/".equals(path) || "/app".equals(path) || path.startsWith("/app/");
            if (!isAppUrl) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/html;charset=UTF-8");
            resp.setContentLength(html.length);
            resp.getOutputStream().write(html);
        }
    }
}
