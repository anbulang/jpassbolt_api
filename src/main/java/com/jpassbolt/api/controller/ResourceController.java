package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.ResourceDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.ResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ResourceController provides REST endpoints for managing password resources.
 * All endpoints enforce permission-based access control.
 */
@Slf4j
@RestController
@RequestMapping("/resources")
@RequiredArgsConstructor
public class ResourceController {

        private final ResourceService resourceService;
        private final UserRepository userRepository;
        private final PermissionRepository permissionRepository;

        /**
         * GET /resources.json
         * Returns all non-deleted resources the current user has READ access to.
         */
        @GetMapping(value = { "", ".json" })
        public ResponseEntity<Map<String, Object>> getAllResources() {
                String userId = getCurrentUserId();
                List<Resource> resources = resourceService.getAccessibleResources(userId);
                List<ResourceDto.Response> responseList = resources.stream()
                                .map(this::toResponseDto)
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                responseList, "/resources.json"));
        }

        /**
         * GET /resources/{id}.json
         * Returns a single resource by ID. Requires READ permission.
         */
        @GetMapping("/{id}.json")
        public ResponseEntity<Map<String, Object>> getResource(@PathVariable String id) {
                String userId = getCurrentUserId();

                // Check READ permission
                if (!permissionRepository.userHasAccess(id, userId, Permission.READ)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", "You are not authorized to access this resource.",
                                                        null, "/resources/" + id + ".json"));
                }

                return resourceService.getResourceById(id)
                                .map(resource -> {
                                        ResourceDto.Response response = toResponseDto(resource);
                                        List<Secret> secrets = resourceService.getSecretsForResource(id);
                                        response.setSecrets(secrets.stream()
                                                        .map(this::toSecretResponseDto)
                                                        .collect(Collectors.toList()));
                                        return ResponseEntity
                                                        .ok(createResponse("success", "The operation was successful.",
                                                                        response, "/resources/" + id + ".json"));
                                })
                                .orElse(ResponseEntity.status(404)
                                                .body(createResponse("error", "Resource not found.", null,
                                                                "/resources/" + id + ".json")));
        }

        /**
         * POST /resources.json
         * Creates a new resource. Creator automatically gets OWNER permission.
         */
        @PostMapping(value = { "", ".json" })
        public ResponseEntity<Map<String, Object>> createResource(@RequestBody ResourceDto.CreateRequest request) {
                String userId = getCurrentUserId();

                try {
                        Resource resource = resourceService.createResource(request, userId);
                        ResourceDto.Response response = toResponseDto(resource);

                        List<Secret> secrets = resourceService.getSecretsForResource(resource.getId());
                        response.setSecrets(secrets.stream()
                                        .map(this::toSecretResponseDto)
                                        .collect(Collectors.toList()));

                        return ResponseEntity.status(201)
                                        .body(createResponse("success", "The resource was created.", response,
                                                        "/resources.json"));
                } catch (Exception e) {
                        log.error("Error creating resource", e);
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), null, "/resources.json"));
                }
        }

        /**
         * PUT /resources/{id}.json
         * Updates an existing resource. Requires UPDATE permission.
         */
        @PutMapping("/{id}.json")
        public ResponseEntity<Map<String, Object>> updateResource(
                        @PathVariable String id,
                        @RequestBody ResourceDto.UpdateRequest request) {
                String userId = getCurrentUserId();

                // Check UPDATE permission
                if (!permissionRepository.userHasAccess(id, userId, Permission.UPDATE)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", "You are not authorized to update this resource.",
                                                        null, "/resources/" + id + ".json"));
                }

                return resourceService.updateResource(id, request, userId)
                                .map(resource -> {
                                        ResourceDto.Response response = toResponseDto(resource);
                                        return ResponseEntity.ok(createResponse("success", "The resource was updated.",
                                                        response, "/resources/" + id + ".json"));
                                })
                                .orElse(ResponseEntity.status(404)
                                                .body(createResponse("error", "Resource not found.", null,
                                                                "/resources/" + id + ".json")));
        }

        /**
         * DELETE /resources/{id}.json
         * Soft deletes a resource. Requires OWNER permission.
         */
        @DeleteMapping("/{id}.json")
        public ResponseEntity<Map<String, Object>> deleteResource(@PathVariable String id) {
                String userId = getCurrentUserId();

                // Check OWNER permission
                if (!permissionRepository.userHasAccess(id, userId, Permission.OWNER)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", "You are not authorized to delete this resource.",
                                                        null, "/resources/" + id + ".json"));
                }

                boolean deleted = resourceService.deleteResource(id, userId);
                if (deleted) {
                        return ResponseEntity.ok(createResponse("success", "The resource was deleted.",
                                        null, "/resources/" + id + ".json"));
                } else {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "Resource not found.", null,
                                                        "/resources/" + id + ".json"));
                }
        }

        private ResourceDto.Response toResponseDto(Resource resource) {
                return ResourceDto.Response.builder()
                                .id(resource.getId())
                                .name(resource.getName())
                                .username(resource.getUsername())
                                .uri(resource.getUri())
                                .description(resource.getDescription())
                                .deleted(resource.getDeleted())
                                .expired(resource.getExpired())
                                .created(resource.getCreated())
                                .modified(resource.getModified())
                                .createdBy(resource.getCreatedBy())
                                .modifiedBy(resource.getModifiedBy())
                                .resourceTypeId(resource.getResourceTypeId())
                                .build();
        }

        private ResourceDto.SecretResponse toSecretResponseDto(Secret secret) {
                return ResourceDto.SecretResponse.builder()
                                .id(secret.getId())
                                .userId(secret.getUserId())
                                .resourceId(secret.getResourceId())
                                .data(secret.getData())
                                .created(secret.getCreated())
                                .modified(secret.getModified())
                                .build();
        }

        private Map<String, Object> createResponse(String status, String message, Object body, String url) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("header", Map.of(
                                "id", java.util.UUID.randomUUID().toString(),
                                "status", status,
                                "servertime", System.currentTimeMillis() / 1000,
                                "code", "success".equals(status) ? 200 : 400,
                                "message", message,
                                "url", url));
                response.put("body", body != null ? body : new LinkedHashMap<>());
                return response;
        }

        private String getCurrentUserId() {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || auth.getName() == null) {
                        throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
                }
                String username = auth.getName();
                Optional<User> user = userRepository.findByUsername(username);
                return user.map(User::getId)
                                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                                                "User not found: " + username));
        }
}
