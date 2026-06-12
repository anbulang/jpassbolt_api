package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTOs for the user management endpoints
 * (POST /users.json, PUT /users/{id}.json, DELETE /users/{id}.json).
 *
 * <p>
 * Pure transport classes — zero business logic. Responses are NOT modelled
 * here: the userIndexAndView shape is deeply nested
 * (profile/avatar/role/gpgkey/groups_users), so controllers render it with
 * hand-built LinkedHashMaps (see UsersController#toUserDetailMap), following
 * the existing project convention.
 * </p>
 */
public class UserDto {

    /**
     * Body of POST /users.json (OpenAPI schemas/userAdd).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {

        private String username;

        @JsonProperty("role_id")
        private String roleId;

        private ProfilePayload profile;
    }

    /**
     * Nested profile payload shared by create/update requests.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfilePayload {

        @JsonProperty("first_name")
        private String firstName;

        @JsonProperty("last_name")
        private String lastName;

        /**
         * Accepted but ignored — real avatar upload/storage belongs to the
         * avatars cluster (PHP also treats an avatar object without file as
         * a delete/no-op). TODO(avatars): wire file handling.
         */
        private Object avatar;
    }

    /**
     * Body of PUT /users/{id}.json (OpenAPI schemas/userUpdate).
     *
     * <p>
     * Note: {@code disabled} is declared boolean in the OpenAPI spec but the
     * DB column is a datetime and PHP validates it with dateTime — we accept
     * an ISO-8601 datetime string. {@code gpgkey} / {@code groups_user} /
     * {@code role} are only declared so the controller can detect and reject
     * them; all other unknown keys (username, active, ...) are silently
     * dropped by Jackson, mirroring PHP's accessibleFields whitelist.
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {

        @JsonProperty("role_id")
        private String roleId;

        private String disabled;

        private ProfilePayload profile;

        /** Present in payload → 400 "Updating the OpenPGP key is not allowed." */
        private Object gpgkey;

        /** Present in payload → 400 "Updating the groups is not allowed." */
        @JsonProperty("groups_user")
        private Object groupsUser;

        /** Present in payload from a non-admin → 403 (role edit refused). */
        private Object role;
    }

    /**
     * Optional body of DELETE /users/{id}.json.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteRequest {

        private Transfer transfer;
    }

    /**
     * Ownership transfer instructions for user deletion.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Transfer {

        private List<TransferOwner> owners;

        /**
         * Group manager transfers — Groups are not implemented yet, the key
         * is accepted and ignored. TODO(groups-crud): support managers.
         */
        private List<Object> managers;
    }

    /**
     * A permission to promote to OWNER as part of a deletion transfer.
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
}
