package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTOs for the JWT authentication channel (/auth/jwt/*). The GpgAuth channel
 * keeps using {@link AuthDto} — the two are never mixed.
 *
 * <p>
 * Spec references (docs/ref_files/plugin-redoc-0.yaml): loginRequest,
 * loginResponse, refreshRequest, refreshResponse, logout schemas.
 * </p>
 */
public class JwtAuthDto {

    /**
     * POST /auth/jwt/login.json request body.
     * challenge = gpg_encrypt(gpg_sign(challenge_message, user_key), server_key)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        @JsonProperty("user_id")
        private String userId;

        private String challenge;
    }

    /**
     * POST /auth/jwt/refresh.json request body (payload mode). The cookie
     * mode (empty body + refresh_token cookie) is also supported, as in PHP.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefreshRequest {
        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("refresh_token")
        private String refreshToken;
    }

    /**
     * POST /auth/jwt/logout.json request body. Nullable: an empty body means
     * "revoke all the current user's refresh tokens" (see the spec's
     * revokeAllSessions example).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogoutRequest {
        @JsonProperty("refresh_token")
        private String refreshToken;
    }

    /**
     * Clear-text challenge payload exchanged inside the armored PGP message
     * (PHP GpgJwtAuthenticator::verifyChallenge /
     * JwtArmoredChallengeService::makeArmoredChallenge).
     * Inbound (client login challenge): version, domain, verify_token,
     * verify_token_expiry. Outbound (server response challenge): version,
     * domain, access_token, refresh_token, verify_token.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChallengePayload {
        private String version;

        private String domain;

        @JsonProperty("verify_token")
        private String verifyToken;

        @JsonProperty("verify_token_expiry")
        private Long verifyTokenExpiry;

        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("refresh_token")
        private String refreshToken;
    }
}
