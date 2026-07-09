package com.jpassbolt.api.service;

import com.jpassbolt.api.config.SettingsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security-focused unit tests for {@link PublicBaseUrlResolver}. The whole
 * reason this class exists is to build recovery-email links from the request
 * origin WITHOUT opening a Host-header-injection / password-reset-poisoning
 * hole: a spoofed {@code Host}/{@code X-Forwarded-Host}/{@code X-Forwarded-Proto}
 * must NOT leak into the link; only true loopback, {@code *.localhost}, and
 * explicitly allowlisted hosts are honored.
 */
class PublicBaseUrlResolverTest {

    private static final String CONFIGURED = "http://config.example:8080";

    private PublicBaseUrlResolver resolver(String override, String trustedHosts) {
        SettingsProperties settings = mock(SettingsProperties.class);
        when(settings.getFullBaseUrl()).thenReturn(CONFIGURED);
        PublicBaseUrlResolver r = new PublicBaseUrlResolver(settings);
        ReflectionTestUtils.setField(r, "override", override);
        ReflectionTestUtils.setField(r, "trustedHostsCsv", trustedHosts);
        return r;
    }

    private void bindRequest(String serverName, int port, String forwardedHost) {
        bindRequest(serverName, port, forwardedHost, null, "http");
    }

    private void bindRequest(String serverName, int port, String forwardedHost,
                             String forwardedProto, String scheme) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setScheme(scheme);
        req.setServerName(serverName);
        req.setServerPort(port);
        if (forwardedHost != null) {
            req.addHeader("X-Forwarded-Host", forwardedHost);
        }
        if (forwardedProto != null) {
            req.addHeader("X-Forwarded-Proto", forwardedProto);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void trustsLocalhostRequestOriginWithPort() {
        bindRequest("localhost", 8090, null);
        assertThat(resolver("", "").resolve()).isEqualTo("http://localhost:8090");
    }

    @Test
    void trustsLoopbackIpv4Literal() {
        bindRequest("127.0.0.1", 8090, null);
        assertThat(resolver("", "").resolve()).isEqualTo("http://127.0.0.1:8090");
    }

    @Test
    void trustsLocalhostSubdomain() {
        // *.localhost is RFC 6761 loopback-forced in browsers, so it is safe.
        bindRequest("app.localhost", 8090, null);
        assertThat(resolver("", "").resolve()).isEqualTo("http://app.localhost:8090");
    }

    @Test
    void bracketsIpv6LoopbackLiteral() {
        bindRequest("::1", 8090, null);
        assertThat(resolver("", "").resolve()).isEqualTo("http://[::1]:8090");
    }

    @Test
    void rejectsAttackerDomainStartingWith127() {
        // Finding A: "127.0.0.1.attacker.example" is an attacker-registrable
        // PUBLIC hostname whose DNS points wherever the attacker wants. A naive
        // startsWith("127.") would trust it and poison the recovery link; the
        // strict dotted-quad check must reject it and fall back to config.
        bindRequest("127.0.0.1.attacker.example", 80, null);
        assertThat(resolver("", "").resolve()).isEqualTo(CONFIGURED);
    }

    @Test
    void rejectsDotLocalByDefault() {
        // Finding B: ".local" (mDNS) can resolve to an arbitrary LAN host, so it
        // is NOT auto-trusted anymore — it must be explicitly allowlisted.
        bindRequest("passbolt.local", 8090, null);
        assertThat(resolver("", "").resolve()).isEqualTo(CONFIGURED);
    }

    @Test
    void honorsDotLocalWhenAllowlisted() {
        bindRequest("passbolt.local", 8090, null);
        assertThat(resolver("", "passbolt.local").resolve())
                .isEqualTo("http://passbolt.local:8090");
    }

    @Test
    void rejectsSpoofedExternalHostAndFallsBackToConfig() {
        bindRequest("evil.attacker.example", 80, null);
        assertThat(resolver("", "").resolve()).isEqualTo(CONFIGURED);
    }

    @Test
    void rejectsSpoofedForwardedHost() {
        // Attacker sets X-Forwarded-Host even though they reach a local server.
        bindRequest("localhost", 8090, "evil.attacker.example");
        assertThat(resolver("", "").resolve()).isEqualTo(CONFIGURED);
    }

    @Test
    void ignoresSpoofedForwardedProtoScheme() {
        // Finding D: a "javascript:" (or any non-http/https) X-Forwarded-Proto
        // must never reach the link scheme — it falls back to the servlet scheme.
        bindRequest("localhost", 8090, null, "javascript:alert(1)//", "http");
        assertThat(resolver("", "").resolve()).isEqualTo("http://localhost:8090");
    }

    @Test
    void honorsHttpsForwardedProto() {
        bindRequest("localhost", 8090, null, "https", "http");
        assertThat(resolver("", "").resolve()).isEqualTo("https://localhost:8090");
    }

    @Test
    void takesFirstValueOfMultiProxyForwardedProto() {
        // "https, http" (multi-hop) must not produce a malformed "https, http://…".
        bindRequest("localhost", 8090, null, "https, http", "http");
        assertThat(resolver("", "").resolve()).isEqualTo("https://localhost:8090");
    }

    @Test
    void honorsForwardedHostWhenAllowlisted() {
        bindRequest("localhost", 8090, "vault.company.com");
        assertThat(resolver("", "vault.company.com").resolve())
                .isEqualTo("http://vault.company.com");
    }

    @Test
    void overrideWinsOverEverything() {
        bindRequest("evil.attacker.example", 80, "evil.attacker.example");
        assertThat(resolver("https://vault.company.com/", "").resolve())
                .isEqualTo("https://vault.company.com");
    }

    @Test
    void fallsBackToConfigWhenNoRequestInScope() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(resolver("", "").resolve()).isEqualTo(CONFIGURED);
    }
}
