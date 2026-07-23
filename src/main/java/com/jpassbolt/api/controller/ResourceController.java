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
import com.jpassbolt.api.service.SecretAccessService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
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

        /** Path identifiers must be well-formed UUIDs before they reach the data layer. */
        private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
                        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

        private final ResourceService resourceService;
        private final FavoriteService favoriteService;
        private final UserRepository userRepository;
        private final PermissionRepository permissionRepository;
        private final SecretAccessService secretAccessService;

        private static boolean isUuid(String value) {
                return value != null && UUID_PATTERN.matcher(value).matches();
        }

        /**
         * GET /resources.json
         * Returns all non-deleted resources the current user has READ access to
         * (directly or through a group). Supports the OpenAPI-declared filters
         * filter[is-favorite], filter[is-owned-by-me], filter[is-shared-with-me],
         * filter[is-shared-with-group] and the contains contain[favorite],
         * contain[permission] (both integer enum [0,1]).
         *
         * <p>
         * Filters compose with AND, exactly as PHP accumulates independent
         * where() clauses in ResourcesFindersTrait::findIndex. The accessible
         * set (READ and up, group-inclusive) is always the base — every filter
         * only ever narrows it, so none of them can widen access.
         * </p>
         */
        @GetMapping({ "/resources", "/resources.json" })
        public ResponseEntity<Map<String, Object>> getAllResources(
                        @RequestParam(name = "filter[is-favorite]", required = false) Boolean isFavorite,
                        @RequestParam(name = "filter[is-owned-by-me]", required = false) Boolean isOwnedByMe,
                        @RequestParam(name = "filter[is-shared-with-me]", required = false) Boolean isSharedWithMe,
                        @RequestParam(name = "filter[is-shared-with-group]", required = false) String isSharedWithGroup,
                        @RequestParam(name = "contain[favorite]", required = false) Integer containFavorite,
                        @RequestParam(name = "contain[permission]", required = false) Integer containPermission) {
                String userId = getCurrentUserId();

                // PHP QueryStringComponent::validateFilterGroup rejects a
                // non-UUID group id before the finder ever runs; the message is
                // assembled by validateQueryItems as 'Invalid filter. ' + the
                // CakeException text. Mirrored verbatim.
                if (isSharedWithGroup != null && !isUuid(isSharedWithGroup)) {
                        return ResponseEntity.badRequest()
                                        .body(createResponse("error", "Invalid filter. \"" + isSharedWithGroup
                                                        + "\" is not a valid group id for filter is-shared-with-group.",
                                                        null, "/resources.json"));
                }

                List<Resource> resources = resourceService.getAccessibleResources(userId);

                Map<String, Favorite> favMap = (isFavorite != null
                                || Integer.valueOf(1).equals(containFavorite))
                                                ? favoriteService.getFavoritesByResourceId(userId)
                                                : Map.of();
                // Value-tested, unlike the two below: PHP guards this with
                // isset() but then branches on the VALUE — `=1` keeps only
                // favorites (innerJoinWith('Favorites')), `=0` EXCLUDES them
                // (notMatching('Favorites')). Omitting the parameter is the
                // only way not to filter. Both directions merely narrow the
                // accessible set, so neither can widen access.
                if (isFavorite != null) {
                        final boolean keepFavorites = isFavorite;
                        resources = resources.stream()
                                        .filter(r -> favMap.containsKey(r.getId()) == keepFavorites)
                                        .collect(Collectors.toList());
                }

                // is-owned-by-me / is-shared-with-me are exact complements over
                // the same owner set, so ONE query feeds both.
                //
                // Presence-, not value-tested on purpose: PHP guards these two
                // with isset($options['filter']['is-owned-by-me']), and isset()
                // is true for a literal false — QueryStringComponent has already
                // normalised the raw string to a real boolean by then. So
                // `?filter[is-owned-by-me]=0` applies the filter identically to
                // `=1` upstream. (Contrast filter[is-favorite] just above, which
                // PHP value-tests: there =0 means "exclude favorites".) The
                // official plugin only ever sends =1, so this quirk is
                // unobservable in practice — but we match the reference rather
                // than the more intuitive reading.
                if (isOwnedByMe != null || isSharedWithMe != null) {
                        // findAccessibleResourceIdsIncludingGroups(_, OWNER) is
                        // exactly PHP findAcosByAroIsOwner(checkGroupsUsers=true):
                        // its `type >= OWNER` and PHP's `type = OWNER` coincide
                        // because OWNER(15) is the top of the 1/7/15 ladder.
                        // Ownership here is a permission fact, unrelated to
                        // created_by, and it counts groups the user belongs to.
                        Set<String> ownedIds = new HashSet<>(permissionRepository
                                        .findAccessibleResourceIdsIncludingGroups(userId, Permission.OWNER));
                        if (isOwnedByMe != null) {
                                resources = resources.stream()
                                                .filter(r -> ownedIds.contains(r.getId()))
                                                .collect(Collectors.toList());
                        }
                        if (isSharedWithMe != null) {
                                // "Accessible AND not owner (directly or via a
                                // group)" — PHP _filterQuerySharedWithUser is a
                                // bare NOT IN over the same owner subquery, with
                                // the base accessible filter supplying the
                                // "accessible" half.
                                resources = resources.stream()
                                                .filter(r -> !ownedIds.contains(r.getId()))
                                                .collect(Collectors.toList());
                        }
                }

                if (isSharedWithGroup != null) {
                        // Group's own permission rows, any type, group NOT
                        // expanded to members (see findResourceIdsSharedWithAro).
                        // No membership check exists in PHP either: intersecting
                        // with the caller's accessible base set is what keeps an
                        // arbitrary group id from leaking anything.
                        Set<String> sharedWithGroupIds = new HashSet<>(
                                        permissionRepository.findResourceIdsSharedWithAro(isSharedWithGroup));
                        resources = resources.stream()
                                        .filter(r -> sharedWithGroupIds.contains(r.getId()))
                                        .collect(Collectors.toList());
                }

                // contain[permission] resolves AFTER filtering so the lookup only
                // covers rows that will actually be rendered. Unlike PHP — where
                // the contain doubles as an INNER-join access filter — this can
                // never add or drop a row: the base set is already READ-and-up,
                // which spans every permission level.
                final Map<String, Permission> highestPermissions = Integer.valueOf(1).equals(containPermission)
                                ? findHighestPermissions(userId, resources)
                                : Map.of();

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
                                        if (Integer.valueOf(1).equals(containPermission)) {
                                                Permission p = highestPermissions.get(r.getId());
                                                if (p != null) {
                                                        dto.setPermission(toPermissionDto(p));
                                                }
                                        }
                                        return dto;
                                })
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                responseList, "/resources.json"));
        }

        /**
         * The caller's highest permission per resource — PHP
         * findHighestByAcoAndAro (ORDER BY type DESC LIMIT 1) batched into a
         * single query. The winner may be a 'Group' row when a group grants more
         * than the user's own row. Ties (same type from two AROs) are unordered
         * in PHP too, so no tie-break is invented here.
         */
        private Map<String, Permission> findHighestPermissions(String userId, List<Resource> resources) {
                if (resources.isEmpty()) {
                        // JPQL `IN :emptyCollection` is not portable — short-circuit.
                        return Map.of();
                }
                List<String> resourceIds = resources.stream()
                                .map(Resource::getId)
                                .collect(Collectors.toList());
                return permissionRepository.findUserAndGroupPermissionsForResources(userId, resourceIds).stream()
                                .collect(Collectors.toMap(Permission::getAcoForeignKey, Function.identity(),
                                                BinaryOperator.maxBy(Comparator.comparingInt(Permission::getType))));
        }

        private ResourceDto.PermissionResponse toPermissionDto(Permission permission) {
                return ResourceDto.PermissionResponse.builder()
                                .id(permission.getId())
                                .aco(permission.getAco())
                                .acoForeignKey(permission.getAcoForeignKey())
                                .aro(permission.getAro())
                                .aroForeignKey(permission.getAroForeignKey())
                                .type(permission.getType())
                                .created(permission.getCreated())
                                .modified(permission.getModified())
                                .build();
        }

        /**
         * GET /resources/{id}.json
         * Returns a single resource by ID. Requires READ permission.
         */
        @GetMapping("/resources/{id}.json")
        public ResponseEntity<Map<String, Object>> getResource(@PathVariable String id) {
                String userId = getCurrentUserId();

                if (!isUuid(id)) {
                        return ResponseEntity.badRequest()
                                        .body(createResponse("error", "The resource identifier should be a valid UUID.",
                                                        null, "/resources/" + id + ".json"));
                }

                // No READ access answers exactly like "does not exist" — PHP
                // ResourcesViewController throws only NotFoundException, so a 403
                // here would let anyone probe which resource UUIDs are real.
                if (!permissionRepository.userHasAccessIncludingGroups(id, userId, Permission.READ)) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The resource does not exist.",
                                                        null, "/resources/" + id + ".json"));
                }

                return resourceService.getResourceById(id)
                                .map(resource -> {
                                        ResourceDto.Response response = toResponseDto(resource);
                                        List<Secret> secrets = resourceService.getSecretsForResource(id);
                                        response.setSecrets(secrets.stream()
                                                        .map(this::toSecretResponseDto)
                                                        .collect(Collectors.toList()));
                                        // Audit: record the caller reading their own secret of this
                                        // resource. Mirrors PHP ResourcesViewController::_logSecretAccesses
                                        // (which contains only the requesting user's secret). Best-effort.
                                        secretAccessService.logCallerSecretAccess(userId, secrets);
                                        return ResponseEntity
                                                        .ok(createResponse("success", "The operation was successful.",
                                                                        response, "/resources/" + id + ".json"));
                                })
                                .orElse(ResponseEntity.status(404)
                                                .body(createResponse("error", "The resource does not exist.", null,
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

                if (!isUuid(id)) {
                        return ResponseEntity.badRequest()
                                        .body(createResponse("error", "The resource identifier should be a valid UUID.",
                                                        null, "/resources/" + id + ".json"));
                }

                // Same 403/404 split as delete: a caller with no access at all must
                // not learn whether the resource exists.
                if (!permissionRepository.userHasAccessIncludingGroups(id, userId, Permission.UPDATE)) {
                        if (permissionRepository.userHasAccessIncludingGroups(id, userId, Permission.READ)) {
                                return ResponseEntity.status(403)
                                                .body(createResponse("error",
                                                                "You are not authorized to update this resource.",
                                                                null, "/resources/" + id + ".json"));
                        }
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The resource does not exist.", null,
                                                        "/resources/" + id + ".json"));
                }

                return resourceService.updateResource(id, request, userId)
                                .map(resource -> {
                                        ResourceDto.Response response = toResponseDto(resource);
                                        return ResponseEntity.ok(createResponse("success", "The resource was updated.",
                                                        response, "/resources/" + id + ".json"));
                                })
                                .orElse(ResponseEntity.status(404)
                                                .body(createResponse("error", "The resource does not exist.", null,
                                                                "/resources/" + id + ".json")));
        }

        /**
         * DELETE /resources/{id}.json
         * Soft deletes a resource. Requires OWNER permission.
         */
        @DeleteMapping("/resources/{id}.json")
        public ResponseEntity<Map<String, Object>> deleteResource(@PathVariable String id) {
                String userId = getCurrentUserId();

                if (!isUuid(id)) {
                        return ResponseEntity.badRequest()
                                        .body(createResponse("error", "The resource identifier should be a valid UUID.",
                                                        null, "/resources/" + id + ".json"));
                }

                // Deleting needs UPDATE, not OWNER (PHP ResourcesTable::softDelete
                // asserts Permission::UPDATE). When the caller falls short, PHP
                // ResourcesDeleteController::_handleDeleteError splits the answer:
                // any access at all -> 403, none -> 404, so a bare 403 would leak
                // the existence of resources the caller cannot see.
                if (!permissionRepository.userHasAccessIncludingGroups(id, userId, Permission.UPDATE)) {
                        if (permissionRepository.userHasAccessIncludingGroups(id, userId, Permission.READ)) {
                                return ResponseEntity.status(403)
                                                .body(createResponse("error",
                                                                "You do not have the permission to delete this resource.",
                                                                null, "/resources/" + id + ".json"));
                        }
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The resource does not exist.", null,
                                                        "/resources/" + id + ".json"));
                }

                boolean deleted = resourceService.deleteResource(id, userId);
                if (deleted) {
                        return ResponseEntity.ok(createResponse("success", "The resource was deleted.",
                                        null, "/resources/" + id + ".json"));
                } else {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The resource does not exist.", null,
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
