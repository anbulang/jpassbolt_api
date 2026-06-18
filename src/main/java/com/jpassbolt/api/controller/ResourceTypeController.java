package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.ResourceTypeDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.ResourceTypeService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ResourceTypeController provides resource type endpoints, compatible with the
 * Passbolt v4/v5 API:
 *
 * <ul>
 *   <li>GET /resource-types.json — active types (deleted IS NULL), v4 AND v5
 *       (matches PHP with passbolt.v5.enabled=true, the default; no slug-version
 *       filter). Any authenticated user, no role check.</li>
 *   <li>GET /resource-types/{id}.json — single type by id, no deleted/v5
 *       filtering (PHP Table::get() semantics).</li>
 *   <li>DELETE /resource-types/{id}.json — soft-delete a type (admin only).
 *       Ported from PHP ResourceTypesDeleteController. Response body is JSON null
 *       (spec {@code nullBody}).</li>
 *   <li>PUT /resource-types/{id}.json — restore (undo soft-delete) a type
 *       (admin only). Ported from PHP ResourceTypesUpdateController: the body
 *       MUST be exactly {@code {"deleted": null}} (the only permitted update),
 *       anything else is a 400. Response body is the empty string (spec
 *       {@code emptyStringBody}).</li>
 * </ul>
 *
 * <p>Note on mappings: no class-level @RequestMapping — with Boot 3's
 * PathPatternParser, "/resource-types" combined with ".json" yields
 * "/resource-types/.json" (NOT "/resource-types.json"), so full
 * method-level paths are used instead.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceTypeController {

        private final ResourceTypeService resourceTypeService;
        private final ObjectMapper objectMapper;
        private final UserService userService;
        private final UserRepository userRepository;

        /**
         * GET /resource-types.json
         * Returns all active resource types (v4 and v5), excluding only
         * soft-deleted rows. Any authenticated user.
         */
        @GetMapping({ "/resource-types", "/resource-types.json" })
        public ResponseEntity<Map<String, Object>> index() {
                List<ResourceTypeDto.Response> responseList = resourceTypeService.getResourceTypes().stream()
                                .map(this::toResponseDto)
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                responseList, "/resource-types.json"));
        }

        /**
         * GET /resource-types/{id}.json
         * Returns a single resource type by id. Validation order ported from
         * PHP ResourceTypesViewController: UUID check (400) before lookup (404).
         */
        @GetMapping("/resource-types/{id}.json")
        public ResponseEntity<Map<String, Object>> view(@PathVariable String id) {
                try {
                        UUID.fromString(id);
                } catch (IllegalArgumentException e) {
                        throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                                        "The resource identifier should be a valid UUID.");
                }

                ResourceType resourceType = resourceTypeService.getResourceTypeById(id);

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                toResponseDto(resourceType), "/resource-types/" + id + ".json"));
        }

        /**
         * PUT /resource-types/{id}.json — restore (undo soft-delete). Admin only.
         *
         * <p>Ported from PHP ResourceTypesUpdateController#update: assert admin,
         * assert non-empty body, require the body to be exactly
         * {@code {"deleted": null}} (a missing/non-null {@code deleted} is a 400,
         * and any extra property is a 400), then undo the soft-delete. The
         * request is taken as a raw map so these structural rules can be enforced
         * verbatim — a typed DTO would silently drop unknown properties and lose
         * the "no other properties allowed" check.</p>
         */
        @PutMapping("/resource-types/{id}.json")
        public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                        @RequestBody(required = false) Map<String, Object> body) {
                String url = "/resource-types/" + id + ".json";

                ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
                if (adminGuard != null) {
                        return adminGuard;
                }

                if (body == null || body.isEmpty()) {
                        throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                                        "The request data can not be empty.");
                }
                if (!body.containsKey("deleted") || body.get("deleted") != null) {
                        throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                                        "The deleted field must be set to null.");
                }
                if (body.size() > 1) {
                        throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                                        "It is not allowed to update these resource type properties.");
                }

                resourceTypeService.restoreResourceType(id);

                // spec emptyStringBody: body is the empty string, not {} or null.
                return ResponseEntity.ok(ApiResponse.passthrough("success",
                                "The operation was successful.", "", url));
        }

        /**
         * DELETE /resource-types/{id}.json — soft-delete. Admin only.
         *
         * <p>Ported from PHP ResourceTypesDeleteController#delete. The service
         * enforces UUID/existence and the "cannot delete the last / in-use type"
         * guards. Response body is JSON null (spec {@code nullBody}).</p>
         */
        @DeleteMapping("/resource-types/{id}.json")
        public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
                String url = "/resource-types/" + id + ".json";

                ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
                if (adminGuard != null) {
                        return adminGuard;
                }

                resourceTypeService.deleteResourceType(id);

                return ResponseEntity.ok(ApiResponse.nullBody("success",
                                "The operation was successful.", url));
        }

        /**
         * Map entity to response DTO. The definition column stores a raw JSON
         * string; it must be deserialized to a JSON object here, otherwise the
         * client would receive a doubly-serialized string and the browser
         * extension could not parse the JSON Schema.
         */
        private ResourceTypeDto.Response toResponseDto(ResourceType resourceType) {
                Object definition = null;
                if (resourceType.getDefinition() != null) {
                        try {
                                definition = objectMapper.readTree(resourceType.getDefinition());
                        } catch (Exception e) {
                                log.warn("Failed to parse definition JSON for resource type {} ({}): {}",
                                                resourceType.getId(), resourceType.getSlug(), e.getMessage());
                        }
                }

                return ResourceTypeDto.Response.builder()
                                .id(resourceType.getId())
                                .slug(resourceType.getSlug())
                                .name(resourceType.getName())
                                .description(resourceType.getDescription())
                                .definition(definition)
                                .deleted(resourceType.getDeleted())
                                .created(resourceType.getCreated())
                                .modified(resourceType.getModified())
                                .build();
        }

        private Map<String, Object> createResponse(String status, String message, Object body, String url) {
                // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留原 200/400 code 语义。
                return ApiResponse.withCode(status, message, body, "success".equals(status) ? 200 : 400, url);
        }

        /**
         * Admin gate (PHP {@code accessRestrictedToAdministrators}). Returns a 403
         * response entity when the current user is not an admin, otherwise null.
         * Mirrors MetadataSettingsController#requireAdmin: the body is an EMPTY
         * STRING so the envelope matches the spec's
         * {@code accessRestrictedToAdministrators} response (body type: string).
         */
        private ResponseEntity<Map<String, Object>> requireAdmin(String url) {
                if (!userService.isAdmin(getCurrentUserId())) {
                        return ResponseEntity.status(403).body(ApiResponse.withCode("error",
                                        "Access restricted to administrators.", "", 403, url));
                }
                return null;
        }

        private String getCurrentUserId() {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || auth.getName() == null) {
                        throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
                }
                String username = auth.getName();
                return userRepository.findByUsername(username)
                                .map(User::getId)
                                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                                                "User not found: " + username));
        }
}
