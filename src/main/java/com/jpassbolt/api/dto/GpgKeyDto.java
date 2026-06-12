package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTOs for the GPG public key directory endpoints (/gpgkeys.json).
 *
 * The Response shape follows the OpenAPI "gpgkey" schema: all 13 fields are
 * required by the contract, therefore no @JsonInclude(NON_NULL) is applied —
 * nullable fields (expires, key_created, type, bits) must still be serialized
 * with a null value.
 */
public class GpgKeyDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;

        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("armored_key")
        private String armoredKey;

        private Integer bits;

        private String uid;

        @JsonProperty("key_id")
        private String keyId;

        private String fingerprint;

        private String type;

        private LocalDateTime expires;

        @JsonProperty("key_created")
        private LocalDateTime keyCreated;

        private Boolean deleted;

        private LocalDateTime created;

        private LocalDateTime modified;
    }
}
