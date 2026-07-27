package com.jpassbolt.api.controller;

import com.jpassbolt.api.config.SecurityHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security-header set official Passbolt sends (CakePHP
 * SecurityHeadersMiddleware, see {@link SecurityHeaders}) must be present on
 * every API response — authenticated, anonymous, and error alike.
 *
 * <p>
 * The skeleton page lives in a second Tomcat context that MockMvc cannot reach;
 * its copy of the same headers is covered by
 * {@code com.jpassbolt.api.config.SkeletonPageServletTest}.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithAnonymousUser
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    /** /settings.json is permitAll, so this also proves the headers precede authentication. */
    @Test
    void testAnonymousEndpoint_CarriesOfficialSecurityHeaderSet() throws Exception {
        mockMvc.perform(get("/settings.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(SecurityHeaders.X_CONTENT_TYPE_OPTIONS,
                        SecurityHeaders.X_CONTENT_TYPE_OPTIONS_VALUE))
                .andExpect(header().string(SecurityHeaders.X_DOWNLOAD_OPTIONS,
                        SecurityHeaders.X_DOWNLOAD_OPTIONS_VALUE))
                .andExpect(header().string(SecurityHeaders.X_PERMITTED_CROSS_DOMAIN_POLICIES,
                        SecurityHeaders.X_PERMITTED_CROSS_DOMAIN_POLICIES_VALUE))
                .andExpect(header().string(SecurityHeaders.REFERRER_POLICY,
                        SecurityHeaders.REFERRER_POLICY_VALUE))
                .andExpect(header().string(SecurityHeaders.X_FRAME_OPTIONS,
                        SecurityHeaders.X_FRAME_OPTIONS_VALUE));
    }

    /**
     * SAMEORIGIN, not Spring Security's default DENY: official Passbolt calls
     * {@code setXFrameOptions()} with no argument (CakePHP default
     * {@code sameorigin}). Asserted separately so a future upgrade that resets
     * the default cannot silently pass the bulk assertion above.
     */
    @Test
    void testFrameOptions_IsSameOriginNotDeny() throws Exception {
        mockMvc.perform(get("/settings.json"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }

    /**
     * The strict official CSP (ContentSecurityPolicyMiddleware default) rides on
     * every API response. JSON has no inline anything, so 'self' is free — the
     * skeleton page relaxes it with a nonce, covered by SkeletonPageServletTest.
     */
    @Test
    void testApiResponse_CarriesStrictOfficialCsp() throws Exception {
        mockMvc.perform(get("/settings.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(SecurityHeaders.CONTENT_SECURITY_POLICY,
                        SecurityHeaders.CONTENT_SECURITY_POLICY_VALUE));
    }

    /**
     * A 401 produced by the authentication entry point still goes through
     * HeaderWriterFilter (which writes in a finally block), so an error response
     * is not a hole in the header set.
     */
    @Test
    void testUnauthenticatedProtectedEndpoint_StillCarriesHeaders() throws Exception {
        mockMvc.perform(get("/resource-types.json"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(SecurityHeaders.REFERRER_POLICY,
                        SecurityHeaders.REFERRER_POLICY_VALUE))
                .andExpect(header().string(SecurityHeaders.X_DOWNLOAD_OPTIONS,
                        SecurityHeaders.X_DOWNLOAD_OPTIONS_VALUE))
                .andExpect(header().string(SecurityHeaders.X_PERMITTED_CROSS_DOMAIN_POLICIES,
                        SecurityHeaders.X_PERMITTED_CROSS_DOMAIN_POLICIES_VALUE));
    }
}
