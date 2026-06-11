package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class ResourceDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String name;
        private String username;
        private String uri;
        private String description;

        @JsonProperty("resource_type_id")
        private String resourceTypeId;

        // The encrypted secret data (PGP armored)
        private List<SecretData> secrets;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class SecretData {
            @JsonProperty("user_id")
            private String userId;
            private String data;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String name;
        private String username;
        private String uri;
        private String description;

        @JsonProperty("resource_type_id")
        private String resourceTypeId;

        private List<CreateRequest.SecretData> secrets;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String name;
        private String username;
        private String uri;
        private String description;
        private Boolean deleted;
        private LocalDateTime expired;
        private LocalDateTime created;
        private LocalDateTime modified;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("modified_by")
        private String modifiedBy;

        @JsonProperty("resource_type_id")
        private String resourceTypeId;

        // Optionally include secrets in response
        private List<SecretResponse> secrets;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecretResponse {
        private String id;

        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("resource_id")
        private String resourceId;

        private String data;
        private LocalDateTime created;
        private LocalDateTime modified;
    }
}
