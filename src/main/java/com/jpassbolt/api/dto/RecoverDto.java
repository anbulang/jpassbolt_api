package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTOs for the account-recovery endpoints
 * (POST /users/recover.json, PUT|POST /setup/recover/complete/{userId}.json and
 * PUT|POST /setup/recover/abort/{userId}.json).
 *
 * <p>
 * Pure transport — token fallback logic (legacy {@code authenticationtoken}
 * key used by plugins before v3.6) and recovery-case validation live in
 * RecoverService, not here. The complete/abort bodies intentionally reuse the
 * same shape as {@link SetupDto} so the official plugin can post an identical
 * payload to the setup and recover flows.
 * </p>
 */
public class RecoverDto {

    /**
     * Body of POST /users/recover.json: the username (email) to recover plus
     * an optional recovery {@code case}. PHP UserRecoverService validates the
     * case against ACCOUNT_RECOVERY_CASES ("default" / "lost-passphrase").
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecoverRequest {

        private String username;

        /** "default" | "lost-passphrase"; optional, defaults to "default". */
        @JsonProperty("case")
        private String recoveryCase;
    }

    /**
     * Body of PUT|POST /setup/recover/complete/{userId}.json. Identical shape
     * to SetupDto.CompleteRequest — the submitted gpgkey is a PUBLIC key whose
     * fingerprint must match the user's already-stored key.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompleteRequest {

        @JsonProperty("authentication_token")
        private SetupDto.TokenPayload authenticationToken;

        /** Deprecated key sent by pre-v3.6 plugins; still accepted. */
        @JsonProperty("authenticationtoken")
        private SetupDto.TokenPayload authenticationtokenLegacy;

        private SetupDto.GpgkeyPayload gpgkey;
    }

    /**
     * Body of PUT|POST /setup/recover/abort/{userId}.json: just the recover
     * token to consume.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AbortRequest {

        @JsonProperty("authentication_token")
        private SetupDto.TokenPayload authenticationToken;

        /** Deprecated key sent by pre-v3.6 plugins; still accepted. */
        @JsonProperty("authenticationtoken")
        private SetupDto.TokenPayload authenticationtokenLegacy;
    }
}
