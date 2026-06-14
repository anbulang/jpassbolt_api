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

        // v5 e2ee metadata shape (transport-only). When present, the create path
        // stores the encrypted metadata blob verbatim and leaves name null; when
        // absent the folder follows the v4 plaintext (name) flow.
        private String metadata;

        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonProperty("metadata_key_type")
        private String metadataKeyType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String name;

        // v5 e2ee metadata shape (transport-only). When present, the update path
        // sets the metadata trio and leaves name untouched (v4 vs v5 branch).
        private String metadata;

        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonProperty("metadata_key_type")
        private String metadataKeyType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;

        /**
         * Folder name. NON_NULL so a v5 folder (name stored in the encrypted
         * metadata blob, column null) omits the key — folderV5IndexAndView does
         * not require name — while a v4 folder still emits it.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String name;

        private LocalDateTime created;
        private LocalDateTime modified;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("modified_by")
        private String modifiedBy;

        // v5 e2ee metadata shape (transport-only). NON_NULL keeps v4 responses
        // byte-for-byte unchanged: these keys only appear when a v5 folder
        // carries a metadata blob, and are omitted entirely for v4 rows.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String metadata;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("metadata_key_type")
        private String metadataKeyType;

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
