package com.jpassbolt.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
        for (String path : new String[] { "/", "/app", "/app/passwords" }) {
            ResponseEntity<String> resp = rest.getForEntity(root(path), String.class);
            assertThat(resp.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(resp.getHeaders().getContentType()).isNotNull();
            assertThat(resp.getHeaders().getContentType().toString()).contains("text/html");
            assertThat(resp.getBody()).contains("JPassbolt").contains("data-jpassbolt-extension");
        }
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
