package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO shell for the share endpoints (PUT /share/{foreignModel}/{foreignId}
 * and POST /share/simulate/{foreignModel}/{foreignId}).
 *
 * <p>
 * Pure transport objects — zero business logic, snake_case JSON mapping via
 * {@code @JsonProperty} (project convention, no global naming strategy).
 * </p>
 */
public class ShareDto {

    /**
     * Request body of shareUpdate / shareUpdateDryRun (OpenAPI requestBodies
     * L12469 / L12515): {permissions: permissionUpdate[], secrets: secretAdd[]}.
     * "secrets" is not required on simulation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShareRequest {
        private List<PermissionChange> permissions;
        private List<SecretAdd> secrets;
    }

    /**
     * permissionUpdate (OpenAPI L8604) — every field optional:
     * id locates an existing permission, delete marks it for removal,
     * is_new is informational only (ignored server-side, like PHP).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionChange {
        private String id;

        private String aro;

        @JsonProperty("aro_foreign_key")
        private String aroForeignKey;

        private Integer type;

        private Boolean delete;

        @JsonProperty("is_new")
        private Boolean isNew;
    }

    /**
     * secretAdd (OpenAPI L8624): required data (PGP armored ciphertext),
     * optional user_id / resource_id.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecretAdd {
        @JsonProperty("user_id")
        private String userId;

        private String data;

        @JsonProperty("resource_id")
        private String resourceId;
    }
}
