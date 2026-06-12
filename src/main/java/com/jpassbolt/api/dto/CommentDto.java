package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for the Comments API, matching the OpenAPI schemas
 * commentAdd / commentUpdate / commentView / userIndexAndView.
 */
public class CommentDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String content;

        @JsonProperty("parent_id")
        private String parentId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;

        @JsonProperty("parent_id")
        private String parentId;

        @JsonProperty("foreign_key")
        private String foreignKey;

        @JsonProperty("foreign_model")
        private String foreignModel;

        private String content;
        private LocalDateTime created;
        private LocalDateTime modified;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("modified_by")
        private String modifiedBy;

        @JsonProperty("user_id")
        private String userId;

        // Nested replies (threaded view); omitted on single-comment responses
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<Response> children;

        // Included only when contain[creator]=1 is requested
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private UserResponse creator;

        // Included only when contain[modifier]=1 is requested
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private UserResponse modifier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResponse {
        private String id;

        @JsonProperty("role_id")
        private String roleId;

        private String username;
        private Boolean active;
        private Boolean deleted;
        private LocalDateTime created;
        private LocalDateTime modified;
        private LocalDateTime disabled;
    }
}
