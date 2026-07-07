package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTOs for the self-registration endpoints (port of the PHP CE plugin
 * {@code Passbolt\SelfRegistration}).
 *
 * <ul>
 *   <li>{@link SettingsRequest} — body of POST /self-registration/settings.json
 *       ({@code {provider, data:{allowed_domains}}}).</li>
 *   <li>{@link DryRunRequest} — body of POST /self-registration/dry-run.json
 *       ({@code {email}}).</li>
 * </ul>
 *
 * <p>The actual guest sign-up (POST /users/register.json) reuses
 * {@link UserDto.CreateRequest} (username + profile) — the standard user-create
 * payload, exactly like PHP {@code UsersRegisterController}.</p>
 *
 * <p>Pure transport classes — zero business logic (validation/normalization lives
 * in {@code SelfRegistrationService}).</p>
 */
public class SelfRegistrationDto {

    /**
     * Body of POST /self-registration/settings.json. Mirrors the PHP stored
     * shape {@code {"provider":"email_domains","data":{"allowed_domains":[...]}}}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettingsRequest {

        /** Currently the only valid provider is {@code email_domains}. */
        private String provider;

        private DataPayload data;
    }

    /** The {@code data} object of a settings request: the allowed-domains list. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataPayload {

        @JsonProperty("allowed_domains")
        private List<String> allowedDomains;
    }

    /** Body of POST /self-registration/dry-run.json — the candidate email. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DryRunRequest {

        private String email;
    }
}
