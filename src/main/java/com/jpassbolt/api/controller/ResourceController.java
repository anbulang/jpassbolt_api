package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.FavoriteDto;
import com.jpassbolt.api.dto.ResourceDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Favorite;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.FavoriteService;
import com.jpassbolt.api.service.ResourceService;
import com.jpassbolt.api.util.ApiResponse;
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
 *
 * Note on mappings: no class-level @RequestMapping. With Boot 3's
 * PathPatternParser, a class-level "/resources" combined with a method-level
 * ".json" yields "/resources/.json" (NOT "/resources.json"), so the official
 * plugin's suffixed URLs would 404. Full method-level paths avoid that.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceController {

        private final ResourceService resourceService;
        private final FavoriteService favoriteService;
        private final UserRepository userRepository;
        private final PermissionRepository permissionRepository;

        /**
         * GET /resources.json
         * Returns all non-deleted resources the current user has READ access to.
         * Supports filter[is-favorite] (OpenAPI filterIsFavorite) and
         * contain[favorite] (OpenAPI containFavorite, enum [0,1]).
         */
        @GetMapping({ "/resources", "/resources.json" })
        public ResponseEntity<Map<String, Object>> getAllResources(
                        @RequestParam(name = "filter[is-favorite]", required = false) Boolean isFavorite,
                        @RequestParam(name = "contain[favorite]", required = false) Integer containFavorite) {
                String userId = getCurrentUserId();
                List<Resource> resources = resourceService.getAccessibleResources(userId);

                Map<String, Favorite> favMap = (Boolean.TRUE.equals(isFavorite)
                                || Integer.valueOf(1).equals(containFavorite))
                                                ? favoriteService.getFavoritesByResourceId(userId)
                                                : Map.of();
                if (Boolean.TRUE.equals(isFavorite)) {
                        resources = resources.stream()
                                        .filter(r -> favMap.containsKey(r.getId()))
                                        .collect(Collectors.toList());
                }

                List<ResourceDto.Response> responseList = resources.stream()
                                .map(r -> {
                                        ResourceDto.Response dto = toResponseDto(r);
                                        if (Integer.valueOf(1).equals(containFavorite)) {
                                                Favorite f = favMap.get(r.getId());
                                                // PHP contain semantics: not favorited => "favorite": null
                                                if (f != null) {
                                                        dto.setFavorite(FavoriteDto.Response.builder()
                                                                        .id(f.getId())
                                                                        .userId(f.getUserId())
                                                                        .foreignKey(f.getForeignKey())
                                                                        .foreignModel(f.getForeignModel())
                                                                        .created(f.getCreated())
                                                                        .modified(f.getModified())
                                                                        .build());
                                                }
                                        }
                                        return dto;
                                })
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                responseList, "/resources.json"));
        }

        /**
         * GET /resources/{id}.json
         * Returns a single resource by ID. Requires READ permission.
         */
        @GetMapping("/resources/{id}.json")
        public ResponseEntity<Map<String, Object>> getResource(@PathVariable String id) {
                String userId = getCurrentUserId();

                // Check READ permission
                if (!permissionRepository.userHasAccessIncludingGroups(id, userId, Permission.READ)) {
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
        @PostMapping({ "/resources", "/resources.json" })
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
        @PutMapping("/resources/{id}.json")
        public ResponseEntity<Map<String, Object>> updateResource(
                        @PathVariable String id,
                        @RequestBody ResourceDto.UpdateRequest request) {
                String userId = getCurrentUserId();

                // Check UPDATE permission
                if (!permissionRepository.userHasAccessIncludingGroups(id, userId, Permission.UPDATE)) {
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
        @DeleteMapping("/resources/{id}.json")
        public ResponseEntity<Map<String, Object>> deleteResource(@PathVariable String id) {
                String userId = getCurrentUserId();

                // Check OWNER permission
                if (!permissionRepository.userHasAccessIncludingGroups(id, userId, Permission.OWNER)) {
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
                                .metadata(resource.getMetadata())
                                .metadataKeyId(resource.getMetadataKeyId())
                                .metadataKeyType(resource.getMetadataKeyType())
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
                // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留原 200/400 code 语义。
                return ApiResponse.withCode(status, message, body, "success".equals(status) ? 200 : 400, url);
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
