package com.jpassbolt.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTOs for the MFA endpoints. Pure transport objects — zero business logic.
 *
 * <p>
 * All JSON field names here are single camelCase words matching the PHP form
 * fields ({@code totp}, {@code remember}, {@code otpProvisioningUri},
 * {@code providers}), so no {@code @JsonProperty} mapping is needed.
 * Responses are assembled as hand-built maps in the controller
 * (verified / otpProvisioningUri / otpQrCodeSvg / mfa_providers), hence no
 * Response inner classes.
 * </p>
 */
public class MfaDto {

    /**
     * POST /mfa/verify/{mfaProviderName}.json request body (OpenAPI
     * mfaAttempt): {"totp": "654373", "remember": 0|1}. {@code remember} is
     * an integer enum 0|1 in the spec — truthiness is evaluated as
     * {@code remember != null && remember != 0}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyRequest {
        private String totp;
        private Integer remember;
    }

    /**
     * POST /mfa/setup/totp.json request body (PHP TotpSetupPostController):
     * the client posts back the provisioning uri obtained from the GET
     * together with a currently valid code.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TotpSetupRequest {
        private String otpProvisioningUri;
        private String totp;
    }

    /**
     * POST|PUT /mfa/settings.json request body (PHP
     * MfaOrgSettingsPostController): {"providers": ["totp"]}. An empty array
     * disables MFA for the whole organization.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrgSettingsRequest {
        private List<String> providers;
    }
}
