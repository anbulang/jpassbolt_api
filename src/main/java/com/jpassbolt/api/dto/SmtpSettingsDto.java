package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTOs for the SMTP settings endpoints — port of the PHP CE plugin
 * {@code Passbolt\SmtpSettings} ({@code SmtpSettingsGet/Post/EmailController} +
 * {@code EmailConfigurationForm}).
 *
 * <ul>
 *   <li>{@link SettingsRequest} — body of POST|PUT /smtp/settings.json (the SMTP
 *       transport config: sender, host, port, tls, client, credentials).</li>
 *   <li>{@link TestEmailRequest} — body of POST /smtp/email.json (the same SMTP
 *       config plus the {@code email_test_to} recipient to probe delivery).</li>
 * </ul>
 *
 * <p>{@code tls} and {@code port} are typed as {@link Object} deliberately: the
 * official extension/CLI sends them as either a JSON number/boolean or a string,
 * and PHP's {@code EmailConfigurationForm} coerces both (filter_var BOOLEAN for
 * tls, integer-cast for port). Normalization + validation lives in
 * {@code SmtpSettingsService}; these classes carry zero business logic (iron
 * rule #3).</p>
 */
public class SmtpSettingsDto {

    /** Body of POST|PUT /smtp/settings.json. Mirrors SMTP_SETTINGS_ALLOWED_FIELDS. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettingsRequest {

        @JsonProperty("sender_name")
        private String senderName;

        @JsonProperty("sender_email")
        private String senderEmail;

        private String host;

        /** Truthy → STARTTLS on; PHP maps filter_var(BOOLEAN) → true or null. */
        private Object tls;

        /** Number or numeric string; validated to an int in [1,65535]. */
        private Object port;

        /** Optional SMTP HELO/EHLO client (a valid IP or domain), else null. */
        private String client;

        private String username;

        private String password;
    }

    /**
     * Body of POST /smtp/email.json — the full SMTP config (same fields as
     * {@link SettingsRequest}) plus the required test recipient. Kept as a flat
     * class (not inheritance) so Lombok's {@code @Builder}/{@code @Data} stay
     * simple; the duplication is acceptable for a dumb transport DTO.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestEmailRequest {

        @JsonProperty("sender_name")
        private String senderName;

        @JsonProperty("sender_email")
        private String senderEmail;

        private String host;

        private Object tls;

        private Object port;

        private String client;

        private String username;

        private String password;

        /** Required: where the test email is delivered (PHP EMAIL_TEST_TO). */
        @JsonProperty("email_test_to")
        private String emailTestTo;
    }
}
