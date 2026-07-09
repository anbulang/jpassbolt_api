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
 * hole: a spoofed {@code Host}/{@code X-Forwarded-Host} must NOT leak into the
 * link; only local dev hosts and explicitly allowlisted hosts are honored.
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
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName(serverName);
        req.setServerPort(port);
        if (forwardedHost != null) {
            req.addHeader("X-Forwarded-Host", forwardedHost);
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
    void trustsDotLocalHost() {
        bindRequest("passbolt.local", 8090, null);
        assertThat(resolver("", "").resolve()).isEqualTo("http://passbolt.local:8090");
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
