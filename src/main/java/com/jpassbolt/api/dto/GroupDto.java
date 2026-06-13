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
 * DTOs for the Group endpoints. Pure transfer objects, no business logic.
 * JSON contract follows the OpenAPI spec (docs/ref_files/plugin-redoc-0.yaml):
 * groupAdd / groupUpdate request bodies and groupIndexAndView response.
 */
public class GroupDto {

    /**
     * POST /groups.json request body (OpenAPI groupAdd).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String name;

        @JsonProperty("groups_users")
        private List<GroupUserData> groupsUsers;
    }

    /**
     * groups_users item of the groupAdd request body.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupUserData {
        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("is_admin")
        private Boolean isAdmin;
    }

    /**
     * PUT /groups/{id}.json and PUT /groups/{id}/dry-run.json request body
     * (OpenAPI groupUpdate).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String name;

        @JsonProperty("groups_users")
        private List<GroupUserChange> groupsUsers;

        private List<SecretData> secrets;
    }

    /**
     * groups_users change item of the groupUpdate request body.
     * Without {@code id} (and for a user not yet member) the change is an
     * addition; with {@code delete=true} it is a removal; otherwise a
     * manager-role ({@code is_admin}) update.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupUserChange {
        private String id;

        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("is_admin")
        private Boolean isAdmin;

        private Boolean delete;
    }

    /**
     * secrets item of the groupUpdate request body: the encrypted secret a
     * client provides for a user gaining access to a resource.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecretData {
        @JsonProperty("resource_id")
        private String resourceId;

        @JsonProperty("user_id")
        private String userId;

        private String data;
    }

    /**
     * Optional body of DELETE /groups/{id}.json
     * (PHP GroupsDeleteController transfer support).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteRequest {
        private Transfer transfer;
    }

    /**
     * Ownership transfer instructions for group deletion.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Transfer {
        private List<TransferOwner> owners;
    }

    /**
     * A permission to promote to OWNER(15) as part of a deletion transfer.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferOwner {
        /** Permission id to promote to OWNER(15). */
        private String id;

        /** Resource id the permission applies to. */
        @JsonProperty("aco_foreign_key")
        private String acoForeignKey;
    }

    /**
     * Group response body (OpenAPI groupIndexAndView).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;

        private String name;

        private Boolean deleted;

        private LocalDateTime created;

        private LocalDateTime modified;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("modified_by")
        private String modifiedBy;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("groups_users")
        private List<GroupUserResponse> groupsUsers;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("my_group_user")
        private GroupUserResponse myGroupUser;
    }

    /**
     * groups_users item of the group response body
     * (OpenAPI groupsUsersIndexAndView).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupUserResponse {
        private String id;

        @JsonProperty("group_id")
        private String groupId;

        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("is_admin")
        private Boolean isAdmin;

        private LocalDateTime created;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private UserResponse user;
    }

    /**
     * Embedded user of a groups_users item (contain[groups_users.user]).
     * NOTE: no Profile entity exists in the Java model yet, so the
     * profile sub-object of the official API is not emitted.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResponse {
        private String id;

        private String username;

        @JsonProperty("role_id")
        private String roleId;

        private Boolean active;

        private Boolean deleted;

        /**
         * Disabled timestamp. Required (nullable) on the embedded user in the
         * OpenAPI contract; the PHP User entity exposes a nullable `disabled`
         * DateTime (null for active accounts), so it is always serialized.
         */
        private LocalDateTime disabled;

        private LocalDateTime created;

        private LocalDateTime modified;
    }
}
