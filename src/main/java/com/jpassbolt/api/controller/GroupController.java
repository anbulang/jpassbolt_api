package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.GroupDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * GroupController provides the Passbolt group management endpoints:
 *
 * <ul>
 * <li>GET /groups.json — index (filter[has-users], filter[has-managers],
 * contain[groups_users...], contain[my_group_user])</li>
 * <li>POST /groups.json — create (admin role only)</li>
 * <li>GET /groups/{groupId}.json — view</li>
 * <li>PUT /groups/{groupId}.json — update (group manager or admin)</li>
 * <li>DELETE /groups/{groupId}.json — delete (group manager or admin,
 * blocked while the group is sole owner of a shared resource)</li>
 * <li>PUT /groups/{groupId}/dry-run.json — update dry run (SecretsNeeded /
 * Secrets lists for the client-side re-encryption workflow)</li>
 * <li>DELETE /groups/{groupId}/dry-run.json — delete dry run</li>
 * </ul>
 *
 * Note on mappings: no class-level @RequestMapping. With Boot 3's
 * PathPatternParser, a class-level "/groups" combined with a method-level
 * ".json" yields "/groups/.json" (NOT "/groups.json"), so the official
 * plugin's suffixed URLs would 404. Full method-level paths avoid that.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GroupController {

        private static final String FORBIDDEN_MESSAGE = "You are not authorized to access that location.";
        private static final String SOLE_OWNER_MESSAGE = "The group cannot be deleted. "
                        + "The group should not be sole owner of shared content, "
                        + "transfer the ownership to other users.";

        private final GroupService groupService;
        private final UserRepository userRepository;

        /**
         * GET /groups.json
         * Returns all non-deleted groups.
         */
        @GetMapping({ "/groups", "/groups.json" })
        public ResponseEntity<Map<String, Object>> getGroups(
                        @RequestParam(name = "filter[has-users]", required = false) String hasUsers,
                        @RequestParam(name = "filter[has-managers]", required = false) String hasManagers,
                        @RequestParam(name = "contain[groups_users]", required = false) Integer containGroupsUsers,
                        @RequestParam(name = "contain[groups_users.user]", required = false) Integer containGroupsUsersUser,
                        @RequestParam(name = "contain[groups_users.user.profile]", required = false) Integer containGroupsUsersUserProfile,
                        @RequestParam(name = "contain[groups_users.user.gpgkey]", required = false) Integer containGroupsUsersUserGpgkey,
                        @RequestParam(name = "contain[my_group_user]", required = false) Integer containMyGroupUser) {
                String userId = getCurrentUserId();

                boolean includeUser = isOne(containGroupsUsersUser)
                                || isOne(containGroupsUsersUserProfile)
                                || isOne(containGroupsUsersUserGpgkey);
                boolean includeGroupsUsers = isOne(containGroupsUsers) || includeUser;
                String myUserId = isOne(containMyGroupUser) ? userId : null;

                List<GroupDto.Response> body = groupService.getGroups(hasUsers, hasManagers).stream()
                                .map(g -> toResponseDto(g, includeGroupsUsers, includeUser, myUserId))
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                body, "/groups.json"));
        }

        /**
         * POST /groups.json
         * Creates a group. Only users with the admin role can create groups.
         */
        @PostMapping({ "/groups", "/groups.json" })
        public ResponseEntity<Map<String, Object>> createGroup(@RequestBody GroupDto.CreateRequest request) {
                String userId = getCurrentUserId();

                if (!groupService.isAdmin(userId)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", FORBIDDEN_MESSAGE, null, "/groups.json"));
                }

                Group group = groupService.createGroup(request, userId);
                GroupDto.Response response = toResponseDto(group, true, false, null);
                return ResponseEntity.ok(createResponse("success", "The group has been added successfully.",
                                response, "/groups.json"));
        }

        /**
         * GET /groups/{groupId}.json
         * Returns a single group with its members.
         */
        @GetMapping("/groups/{id}.json")
        public ResponseEntity<Map<String, Object>> getGroup(
                        @PathVariable String id,
                        @RequestParam(name = "contain[my_group_user]", required = false) Integer containMyGroupUser) {
                String userId = getCurrentUserId();
                String url = "/groups/" + id + ".json";

                return groupService.getGroupById(id)
                                .map(group -> ResponseEntity.ok(createResponse("success",
                                                "The operation was successful.",
                                                toResponseDto(group, true, true,
                                                                isOne(containMyGroupUser) ? userId : null),
                                                url)))
                                .orElse(ResponseEntity.status(404)
                                                .body(createResponse("error", "The group does not exist.", null, url)));
        }

        /**
         * PUT /groups/{groupId}.json
         * Updates a group. Requires the group manager role or the admin role.
         */
        @PutMapping("/groups/{id}.json")
        public ResponseEntity<Map<String, Object>> updateGroup(
                        @PathVariable String id,
                        @RequestBody GroupDto.UpdateRequest request) {
                String userId = getCurrentUserId();
                String url = "/groups/" + id + ".json";

                if (groupService.getGroupById(id).isEmpty()) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The group does not exist.", null, url));
                }
                boolean isAdmin = groupService.isAdmin(userId);
                boolean isManager = groupService.isGroupManager(id, userId);
                if (!isAdmin && !isManager) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", FORBIDDEN_MESSAGE, null, url));
                }

                Group group = groupService.updateGroup(id, request, userId, isAdmin, isManager);
                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                toResponseDto(group, true, true, null), url));
        }

        /**
         * DELETE /groups/{groupId}.json
         * Deletes a group. Requires the group manager role or the admin role.
         * Blocked (400) while the group is the sole owner of a shared resource.
         */
        @DeleteMapping("/groups/{id}.json")
        public ResponseEntity<Map<String, Object>> deleteGroup(@PathVariable String id) {
                String userId = getCurrentUserId();
                String url = "/groups/" + id + ".json";

                if (groupService.getGroupById(id).isEmpty()) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error",
                                                        "The group does not exist or has been already deleted.",
                                                        null, url));
                }
                if (!groupService.isAdmin(userId) && !groupService.isGroupManager(id, userId)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", FORBIDDEN_MESSAGE, null, url));
                }

                List<Resource> blocking = groupService.findSoleOwnerBlockingResources(id);
                if (!blocking.isEmpty()) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", SOLE_OWNER_MESSAGE,
                                                        soleOwnerErrorBody(blocking), url));
                }

                groupService.deleteGroup(id, userId);
                return ResponseEntity.ok(createResponse("success", "The group was deleted successfully.",
                                null, url));
        }

        /**
         * PUT /groups/{groupId}/dry-run.json
         * Dry run of a group update. Returns the secrets the client must encrypt
         * for the added members (SecretsNeeded) and the operator's own secrets
         * to decrypt (Secrets). Requires the group manager role or the admin role.
         */
        @PutMapping("/groups/{id}/dry-run.json")
        public ResponseEntity<Map<String, Object>> updateGroupDryRun(
                        @PathVariable String id,
                        @RequestBody GroupDto.UpdateRequest request) {
                String userId = getCurrentUserId();
                String url = "/groups/" + id + "/dry-run.json";

                if (groupService.getGroupById(id).isEmpty()) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The group does not exist.", null, url));
                }
                boolean isAdmin = groupService.isAdmin(userId);
                boolean isManager = groupService.isGroupManager(id, userId);
                if (!isAdmin && !isManager) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", FORBIDDEN_MESSAGE, null, url));
                }

                Map<String, Object> result = groupService.updateDryRun(id, request.getGroupsUsers(),
                                userId, isManager);
                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                toDryRunBody(result), url));
        }

        /**
         * DELETE /groups/{groupId}/dry-run.json
         * Dry run of a group deletion. Returns 200 with the resources the group
         * has access to when the group can be deleted, 400 with the
         * errors.resources.sole_owner list otherwise.
         */
        @DeleteMapping("/groups/{id}/dry-run.json")
        public ResponseEntity<Map<String, Object>> deleteGroupDryRun(@PathVariable String id) {
                String userId = getCurrentUserId();
                String url = "/groups/" + id + "/dry-run.json";

                if (groupService.getGroupById(id).isEmpty()) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error",
                                                        "The group does not exist or has been already deleted.",
                                                        null, url));
                }
                if (!groupService.isAdmin(userId) && !groupService.isGroupManager(id, userId)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", FORBIDDEN_MESSAGE, null, url));
                }

                List<Resource> blocking = groupService.findSoleOwnerBlockingResources(id);
                if (!blocking.isEmpty()) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", SOLE_OWNER_MESSAGE,
                                                        soleOwnerErrorBody(blocking), url));
                }

                List<Map<String, Object>> resources = groupService.getGroupAccessibleResources(id).stream()
                                .map(this::toResourceMap)
                                .collect(Collectors.toList());
                return ResponseEntity.ok(createResponse("success", "The group can be deleted.",
                                resources, url));
        }

        // ------------------------------------------------------------------
        // DTO / body builders
        // ------------------------------------------------------------------

        private GroupDto.Response toResponseDto(Group group, boolean includeGroupsUsers,
                        boolean includeUser, String myUserId) {
                GroupDto.Response.ResponseBuilder builder = GroupDto.Response.builder()
                                .id(group.getId())
                                .name(group.getName())
                                .deleted(group.getDeleted())
                                .created(group.getCreated())
                                .modified(group.getModified())
                                .createdBy(group.getCreatedBy())
                                .modifiedBy(group.getModifiedBy());

                if (includeGroupsUsers) {
                        builder.groupsUsers(groupService.getGroupUsers(group.getId()).stream()
                                        .map(gu -> toGroupUserDto(gu, includeUser))
                                        .collect(Collectors.toList()));
                }
                if (myUserId != null) {
                        groupService.getMyGroupUser(group.getId(), myUserId)
                                        .ifPresent(gu -> builder.myGroupUser(toGroupUserDto(gu, false)));
                }
                return builder.build();
        }

        private GroupDto.GroupUserResponse toGroupUserDto(GroupUser groupUser, boolean includeUser) {
                GroupDto.GroupUserResponse.GroupUserResponseBuilder builder = GroupDto.GroupUserResponse.builder()
                                .id(groupUser.getId())
                                .groupId(groupUser.getGroupId())
                                .userId(groupUser.getUserId())
                                .isAdmin(groupUser.getIsAdmin())
                                .created(groupUser.getCreated());

                if (includeUser && groupUser.getUserId() != null) {
                        userRepository.findById(groupUser.getUserId())
                                        .ifPresent(user -> builder.user(toUserDto(user)));
                }
                return builder.build();
        }

        private GroupDto.UserResponse toUserDto(User user) {
                return GroupDto.UserResponse.builder()
                                .id(user.getId())
                                .username(user.getUsername())
                                .roleId(user.getRoleId())
                                .active(user.getActive())
                                .deleted(user.getDeleted())
                                .created(user.getCreated())
                                .modified(user.getModified())
                                .build();
        }

        /**
         * Format the dry-run service result into the legacy V1 body shape
         * required by the official plugin (OpenAPI groupUpdateDryRun):
         * {"dry-run": {"SecretsNeeded": [{"Secret": {...}}], "Secrets": [{"Secret": {...}}]}}
         */
        @SuppressWarnings("unchecked")
        private Map<String, Object> toDryRunBody(Map<String, Object> result) {
                List<Map<String, String>> secretsNeeded = (List<Map<String, String>>) result
                                .get("secretsNeeded");
                List<Secret> operatorSecrets = (List<Secret>) result.get("secrets");

                List<Map<String, Object>> secretsNeededBody = secretsNeeded.stream()
                                .map(pair -> Map.<String, Object>of("Secret", pair))
                                .collect(Collectors.toList());
                List<Map<String, Object>> secretsBody = operatorSecrets.stream()
                                .map(secret -> {
                                        Map<String, Object> secretMap = new LinkedHashMap<>();
                                        secretMap.put("id", secret.getId());
                                        secretMap.put("resource_id", secret.getResourceId());
                                        secretMap.put("user_id", secret.getUserId());
                                        secretMap.put("data", secret.getData());
                                        return Map.<String, Object>of("Secret", secretMap);
                                })
                                .collect(Collectors.toList());

                Map<String, Object> dryRun = new LinkedHashMap<>();
                dryRun.put("SecretsNeeded", secretsNeededBody);
                dryRun.put("Secrets", secretsBody);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dry-run", dryRun);
                return body;
        }

        /**
         * Body of the sole-owner delete error:
         * {"errors": {"resources": {"sole_owner": [resource, ...]}}}
         */
        private Map<String, Object> soleOwnerErrorBody(List<Resource> blocking) {
                Map<String, Object> soleOwner = new LinkedHashMap<>();
                soleOwner.put("sole_owner", blocking.stream()
                                .map(this::toResourceMap)
                                .collect(Collectors.toList()));

                Map<String, Object> resources = new LinkedHashMap<>();
                resources.put("resources", soleOwner);

                Map<String, Object> errors = new LinkedHashMap<>();
                errors.put("errors", resources);
                return errors;
        }

        private Map<String, Object> toResourceMap(Resource resource) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", resource.getId());
                map.put("name", resource.getName());
                map.put("username", resource.getUsername());
                map.put("uri", resource.getUri());
                map.put("description", resource.getDescription());
                map.put("deleted", resource.getDeleted());
                map.put("created", resource.getCreated());
                map.put("modified", resource.getModified());
                map.put("created_by", resource.getCreatedBy());
                map.put("modified_by", resource.getModifiedBy());
                map.put("resource_type_id", resource.getResourceTypeId());
                map.put("expired", resource.getExpired());
                return map;
        }

        // ------------------------------------------------------------------
        // Shared helpers (same pattern as ResourceController)
        // ------------------------------------------------------------------

        private boolean isOne(Integer value) {
                return Integer.valueOf(1).equals(value);
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
