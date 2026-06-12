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
 * DTOs for the Folders endpoints (OpenAPI folderV4IndexAndView / folderAdd /
 * folderUpdate / move schemas). Transport only — no business logic.
 */
public class FolderDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String name;

        @JsonProperty("folder_parent_id")
        private String folderParentId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String name;
        private LocalDateTime created;
        private LocalDateTime modified;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("modified_by")
        private String modifiedBy;

        /**
         * Parent folder in the CURRENT user's tree (folders_relations), null =
         * root. Always serialized (required + nullable in the OpenAPI schema).
         */
        @JsonProperty("folder_parent_id")
        private String folderParentId;

        /** True when a single user sees the folder. */
        private Boolean personal;

        /** contain[permissions]=1 — all permissions on the folder. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<PermissionResponse> permissions;

        /** contain[children_resources]=1 — direct child resources (user tree). */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("children_resources")
        private List<ResourceDto.Response> childrenResources;

        /** contain[children_folders]=1 — direct child folders (user tree). */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("children_folders")
        private List<Response> childrenFolders;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionResponse {
        private String id;
        private String aco;

        @JsonProperty("aco_foreign_key")
        private String acoForeignKey;

        private String aro;

        @JsonProperty("aro_foreign_key")
        private String aroForeignKey;

        private Integer type;
        private LocalDateTime created;
        private LocalDateTime modified;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoveRequest {
        @JsonProperty("folder_parent_id")
        private String folderParentId;
    }
}
