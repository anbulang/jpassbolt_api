package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTOs for the account setup endpoints
 * (PUT|POST /setup/complete/{userId}.json).
 *
 * <p>
 * Pure transport — token fallback logic (legacy {@code authenticationtoken}
 * key used by plugins before v3.6) lives in SetupService, not here.
 * </p>
 */
public class SetupDto {

    /**
     * Body of PUT /setup/complete/{userId}.json.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompleteRequest {

        @JsonProperty("authentication_token")
        private TokenPayload authenticationToken;

        /** Deprecated key sent by pre-v3.6 plugins; still accepted. */
        @JsonProperty("authenticationtoken")
        private TokenPayload authenticationtokenLegacy;

        private GpgkeyPayload gpgkey;
    }

    /**
     * Wrapper around the register token value.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenPayload {

        private String token;
    }

    /**
     * Wrapper around the user's ASCII-armored OpenPGP public key.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GpgkeyPayload {

        @JsonProperty("armored_key")
        private String armoredKey;
    }
}
