package com.jpassbolt.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.HttpURLConnection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-server test for the domain-root skeleton context (SkeletonPageConfig).
 * Needs a real Tomcat (RANDOM_PORT): the extra root context does not exist in
 * MockMvc. Absolute URLs are used because TestRestTemplate's root URI already
 * includes the /api context path.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Production shape: Spring app under /api, skeleton context at the root.
        // (The shared test yml sets no context-path, and without one the
        // skeleton context is intentionally not mounted.)
        properties = "server.servlet.context-path=/api")
class SkeletonPageIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String root(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void appUrlServesSkeletonHtml() {
        // App shell (/app…) plus the browser-facing auth/guest routes the
        // extension content script attaches to: the sign-in triage and the
        // email-link landing pages must serve the SAME skeleton, not 404.
        for (String path : new String[] {
                "/app", "/app/passwords",
                "/auth/login",
                "/setup/recover/start/2e3d8f6a-1111-4b22-9c33-444455556666/aaaabbbb-cccc-4ddd-8eee-ffff00001111",
                "/setup/install/2e3d8f6a-1111-4b22-9c33-444455556666/aaaabbbb-cccc-4ddd-8eee-ffff00001111",
                "/recover" }) {
            ResponseEntity<String> resp = rest.getForEntity(root(path), String.class);
            assertThat(resp.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(resp.getHeaders().getContentType()).isNotNull();
            assertThat(resp.getHeaders().getContentType().toString()).contains("text/html");
            assertThat(resp.getBody()).contains("JPassbolt").contains("data-jpassbolt-extension");
        }
    }

    @Test
    void rootRedirectsToLogin() throws Exception {
        // Official parity: an unauthenticated browser hitting the domain root is
        // 302-redirected to /auth/login (the skeleton context cannot read the
        // session, so it always mirrors the unauthenticated case). Raw
        // HttpURLConnection because TestRestTemplate follows GET redirects.
        HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(root("/")).toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        assertThat(conn.getResponseCode()).isEqualTo(HttpStatus.FOUND.value());
        assertThat(conn.getHeaderField("Location")).isEqualTo("/auth/login?redirect=%2F");
        conn.disconnect();
    }

    @Test
    void nonAppUrlIs404() {
        ResponseEntity<String> resp = rest.getForEntity(root("/definitely-not-app"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void apiContextStillServed() {
        ResponseEntity<String> resp = rest.getForEntity(root("/api/healthcheck/status.json"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"status\"");
    }
}
