package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTOs for the Favorite endpoints.
 * Pure transport objects — no business logic.
 */
public class FavoriteDto {

    /**
     * Response shape of the OpenAPI `favorite` schema.
     * All six fields are required by the spec, so no @JsonInclude(NON_NULL) here.
     * foreign_model is serialized capitalized ("Resource") per the spec enum.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {

        private String id;

        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("foreign_key")
        private String foreignKey;

        @JsonProperty("foreign_model")
        private String foreignModel;

        private LocalDateTime created;

        private LocalDateTime modified;
    }
}
