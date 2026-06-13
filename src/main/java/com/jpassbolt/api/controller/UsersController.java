package com.jpassbolt.api.controller;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.dto.UserDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.UserDeleteService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * UsersController provides REST endpoints for user management:
 * index/view plus admin invite-style creation, whitelist edit, soft delete
 * and delete dry-run (ported from PHP UsersAddController /
 * UsersEditController / UsersDeleteController).
 *
 * Note on mappings: no class-level @RequestMapping. With Boot 3's
 * PathPatternParser, a class-level "/users" combined with a method-level
 * ".json" yields "/users/.json" (NOT "/users.json"), so the official
 * plugin's suffixed URLs would 404. Full method-level paths avoid that.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UsersController {

        private static final Pattern UUID_PATTERN = Pattern.compile(
                        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

        private final UserRepository userRepository;
        private final RoleRepository roleRepository;
        private final ProfileRepository profileRepository;
        private final GpgKeyRepository gpgKeyRepository;
        private final GroupUserRepository groupUserRepository;
        private final SettingsProperties settingsProperties;
        private final UserService userService;
        private final UserDeleteService userDeleteService;

        /**
         * GET /users.json
         * Returns non-deleted users in userIndexAndView shape (the official
         * plugin needs profile/role/gpgkey on the index).
         *
         * <p>
         * Whitelist (PHP UsersIndexController): filter[search] is a
         * case-insensitive match across username + profile first/last name
         * (the plugin's share dialog leans on it the most) pushed down to the
         * repository; filter[is-active] is admin-only — a non-admin caller is
         * always pinned to active users, an admin with no filter sees both
         * active and inactive. Results are ordered by username asc (PHP
         * default order Users.username), so the listing no longer relies on
         * JPA's unspecified findAll order. The has-groups / has-access /
         * is-admin / contain controls of the PHP whitelist are intentionally
         * NOT implemented yet (the plugin does not require them here).
         * </p>
         */
        @GetMapping({ "/users", "/users.json" })
        public ResponseEntity<Map<String, Object>> getAllUsers(
                        @RequestParam(name = "filter[search]", required = false) String search,
                        @RequestParam(name = "filter[is-active]", required = false) String isActive) {
                boolean actorIsAdmin = isCurrentUserAdmin();

                // is-active is admin-only; everyone else is pinned to active.
                Boolean activeFilter = Boolean.TRUE;
                if (actorIsAdmin) {
                        activeFilter = parseIsActiveFilter(isActive); // null => both
                }

                String term = (search == null || search.isBlank())
                                ? null
                                : "%" + search.trim().toLowerCase() + "%";

                List<User> users = userRepository.findIndex(term, activeFilter,
                                Sort.by(Sort.Direction.ASC, "username"));

                List<Map<String, Object>> userList = users.stream()
                                .map(this::toUserDetailMap)
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                userList, "/users.json"));
        }

        /**
         * Parse admin-only filter[is-active] (PHP QueryStringComponent
         * normalizeBoolean: 0/1/true/false). A missing value means "no
         * filter" (both active and inactive), an explicit value narrows to
         * that state.
         */
        private Boolean parseIsActiveFilter(String value) {
                if (value == null || value.isBlank()) {
                        return null;
                }
                switch (value.toLowerCase()) {
                        case "0":
                        case "false":
                                return Boolean.FALSE;
                        case "1":
                        case "true":
                                return Boolean.TRUE;
                        default:
                                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                                                "Invalid filter. \"" + value
                                                                + "\" is not a valid value for filter is-active.");
                }
        }

        /**
         * GET /users/{id}.json
         * Returns a single user by ID. Supports the "me" alias (the plugin
         * uses GET /users/me.json heavily); any other non-UUID id is a 400.
         */
        @GetMapping({ "/users/{id}", "/users/{id}.json" })
        public ResponseEntity<Map<String, Object>> getUser(@PathVariable String id) {
                String url = "/users/" + id + ".json";

                String targetId;
                if ("me".equals(id)) {
                        targetId = getCurrentUserId();
                } else if (!isUuid(id)) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "The user identifier should be a valid UUID.", null, url));
                } else {
                        targetId = id;
                }

                return userRepository.findById(targetId)
                                .filter(u -> Boolean.TRUE.equals(u.getActive()) && !Boolean.TRUE.equals(u.getDeleted()))
                                .map(user -> ResponseEntity.ok(createResponse("success",
                                                "The operation was successful.", toUserDetailMap(user), url)))
                                .orElse(ResponseEntity.status(404)
                                                .body(createResponse("error", "The user does not exist.", null, url)));
        }

        /**
         * POST /users.json
         * Admin invite-style creation: the user is saved inactive together
         * with its profile and a register token; activation happens through
         * /setup/complete. The OpenAPI operation only declares 200/400/401
         * but PHP returns 403 for non-admins — PHP behaviour wins.
         */
        @PostMapping({ "/users", "/users.json" })
        public ResponseEntity<Map<String, Object>> addUser(@RequestBody UserDto.CreateRequest request) {
                String url = "/users.json";
                getCurrentUserId(); // 401/404 guard via PassboltApiException

                if (!isCurrentUserAdmin()) {
                        return ResponseEntity.status(403).body(createResponse("error",
                                        "Only administrators can add new users.", null, url));
                }

                try {
                        User created = userService.createUser(request);
                        return ResponseEntity.ok(createResponse("success",
                                        "The user was successfully added. This user now need to complete the setup.",
                                        toUserDetailMap(created), url));
                } catch (UserService.UserValidationException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), e.getErrors(), url));
                } catch (IllegalArgumentException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), null, url));
                }
        }

        /**
         * PUT|POST /users/{id}.json (PHP registers both, routes.php L273).
         * Whitelist edit: profile names for self/admin, role_id/disabled for
         * admin only. Validation order strictly follows the PHP controller.
         */
        @RequestMapping(value = { "/users/{id}", "/users/{id}.json" }, method = { RequestMethod.PUT,
                        RequestMethod.POST })
        public ResponseEntity<Map<String, Object>> updateUser(
                        @PathVariable String id,
                        @RequestBody UserDto.UpdateRequest request) {
                String url = "/users/" + id + ".json";
                String actorId = getCurrentUserId();
                boolean actorIsAdmin = isCurrentUserAdmin();

                // (1) non-admin may only edit themselves
                if (!actorIsAdmin && !actorId.equals(id)) {
                        return ResponseEntity.status(403).body(createResponse("error",
                                        "You are not authorized to access that location.", null, url));
                }
                // (2) id must be a UUID
                if (!isUuid(id)) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "The user identifier should be a valid UUID.", null, url));
                }
                // (3) empty payload
                if (request == null || (request.getRoleId() == null && request.getDisabled() == null
                                && request.getProfile() == null && request.getGpgkey() == null
                                && request.getGroupsUser() == null && request.getRole() == null)) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "Some user data should be provided.", null, url));
                }
                // (4) gpgkey can never be updated here
                if (request.getGpgkey() != null) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "Updating the OpenPGP key is not allowed.", null, url));
                }
                // (5) groups can never be updated here
                if (request.getGroupsUser() != null) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "Updating the groups is not allowed.", null, url));
                }
                // (6) only admins may touch the role
                if (!actorIsAdmin && (request.getRole() != null || request.getRoleId() != null)) {
                        return ResponseEntity.status(403).body(createResponse("error",
                                        "You are not authorized to edit the role.", null, url));
                }
                // (7) target must exist and not be soft-deleted.
                // NOTE: PHP throws BadRequestException here (400), NOT the 404
                // the OpenAPI spec declares — the plugin relies on 400.
                Optional<User> target = userRepository.findById(id)
                                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()));
                if (target.isEmpty()) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "The user does not exist or has been deleted.", null, url));
                }

                try {
                        User updated = userService.updateUser(id, request, actorId, actorIsAdmin);
                        return ResponseEntity.ok(createResponse("success",
                                        "The user has been updated successfully.", toUserDetailMap(updated), url));
                } catch (UserService.UserValidationException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), e.getErrors(), url));
                } catch (IllegalArgumentException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), null, url));
                }
        }

        /**
         * DELETE /users/{id}.json
         * Admin-only soft delete with optional ownership transfer and
         * sole-owner protection. Success body is JSON null (nullBody).
         */
        @DeleteMapping({ "/users/{id}", "/users/{id}.json" })
        public ResponseEntity<Map<String, Object>> deleteUser(
                        @PathVariable String id,
                        @RequestBody(required = false) UserDto.DeleteRequest request) {
                String url = "/users/" + id + ".json";

                ResponseEntity<Map<String, Object>> guard = validateDeletePreconditions(id, url);
                if (guard != null) {
                        return guard;
                }
                if (request != null && request.getTransfer() != null
                                && request.getTransfer().getOwners() != null) {
                        for (UserDto.TransferOwner owner : request.getTransfer().getOwners()) {
                                if (!isUuid(owner.getId())) {
                                        return ResponseEntity.status(400).body(createResponse("error",
                                                        "The permissions identifiers must be valid UUID.", null, url));
                                }
                        }
                }

                try {
                        userDeleteService.deleteUser(id, getCurrentUserId(), request);
                        return ResponseEntity.ok(createNullBodyResponse("success",
                                        "The user has been deleted successfully.", url));
                } catch (UserDeleteService.UserDeleteConflictException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), e.getBody(), url));
                } catch (IllegalArgumentException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), null, url));
                }
        }

        /**
         * DELETE /users/{id}/dry-run.json
         * Same preconditions as the real delete, then only the sole-owner
         * check — never reads a transfer, never writes.
         */
        @DeleteMapping({ "/users/{id}/dry-run", "/users/{id}/dry-run.json" })
        public ResponseEntity<Map<String, Object>> deleteUserDryRun(@PathVariable String id) {
                String url = "/users/" + id + "/dry-run.json";

                ResponseEntity<Map<String, Object>> guard = validateDeletePreconditions(id, url);
                if (guard != null) {
                        return guard;
                }

                try {
                        userDeleteService.validateDelete(id);
                        return ResponseEntity.ok(createNullBodyResponse("success",
                                        "The user can be deleted.", url));
                } catch (UserDeleteService.UserDeleteConflictException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), e.getBody(), url));
                }
        }

        /**
         * Shared preconditions of DELETE and dry-run (PHP
         * UsersDeleteController::_validateRequestData): admin-only(403) →
         * UUID(400) → not-self(400) → exists & not deleted(404).
         */
        private ResponseEntity<Map<String, Object>> validateDeletePreconditions(String id, String url) {
                String actorId = getCurrentUserId();
                if (!isCurrentUserAdmin()) {
                        return ResponseEntity.status(403).body(createResponse("error",
                                        "You are not authorized to access that location.", null, url));
                }
                if (!isUuid(id)) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "The user identifier should be a valid UUID.", null, url));
                }
                if (actorId.equals(id)) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "You are not allowed to delete yourself.", null, url));
                }
                Optional<User> target = userRepository.findById(id)
                                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()));
                if (target.isEmpty()) {
                        return ResponseEntity.status(404).body(createResponse("error",
                                        "The user does not exist or has been already deleted.", null, url));
                }
                return null;
        }

        // ------------------------------------------------------------------
        // Rendering helpers
        // ------------------------------------------------------------------

        /**
         * userIndexAndView shape: base user fields + groups_users (real
         * memberships — the plugin's share dialog, group member views and the
         * pre-delete sole-manager hints all rely on them) + profile (with
         * mandatory avatar default URLs) + role + gpgkey + last_logged_in
         * (not tracked yet).
         */
        private Map<String, Object> toUserDetailMap(User user) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", user.getId());
                map.put("role_id", user.getRoleId());
                map.put("username", user.getUsername());
                map.put("active", user.getActive());
                map.put("deleted", user.getDeleted());
                map.put("disabled", user.getDisabled());
                map.put("created", user.getCreated());
                map.put("modified", user.getModified());
                map.put("last_logged_in", null);
                map.put("groups_users", groupUserRepository.findByUserId(user.getId()).stream()
                                .map(this::toGroupUserMap)
                                .collect(Collectors.toList()));
                map.put("profile", profileRepository.findByUserId(user.getId())
                                .map(this::toProfileMap).orElse(null));
                map.put("role", roleRepository.findById(user.getRoleId())
                                .map(this::toRoleMap).orElse(null));
                map.put("gpgkey", gpgKeyRepository.findByUserId(user.getId()).stream()
                                .filter(key -> !Boolean.TRUE.equals(key.getDeleted()))
                                .findFirst()
                                .map(this::toGpgKeyMap).orElse(null));
                return map;
        }

        /**
         * profile.avatar is a REQUIRED child of profile in the OpenAPI spec:
         * always emit the default placeholder URLs (real avatar storage
         * belongs to the avatars cluster).
         */
        private Map<String, Object> toProfileMap(Profile profile) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", profile.getId());
                map.put("user_id", profile.getUserId());
                map.put("first_name", profile.getFirstName());
                map.put("last_name", profile.getLastName());
                map.put("created", profile.getCreated());
                map.put("modified", profile.getModified());
                String base = settingsProperties.getFullBaseUrl();
                Map<String, Object> url = new LinkedHashMap<>();
                url.put("medium", base + "/img/avatar/user_medium.png");
                url.put("small", base + "/img/avatar/user.png");
                Map<String, Object> avatar = new LinkedHashMap<>();
                avatar.put("url", url);
                map.put("avatar", avatar);
                return map;
        }

        private Map<String, Object> toRoleMap(Role role) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", role.getId());
                map.put("name", role.getName());
                map.put("description", role.getDescription());
                map.put("created", role.getCreated());
                map.put("modified", role.getModified());
                return map;
        }

        /** groupsUsersIndexAndView shape (PHP contain groups_users). */
        private Map<String, Object> toGroupUserMap(GroupUser groupUser) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", groupUser.getId());
                map.put("group_id", groupUser.getGroupId());
                map.put("user_id", groupUser.getUserId());
                map.put("is_admin", groupUser.getIsAdmin());
                map.put("created", groupUser.getCreated());
                return map;
        }

        private Map<String, Object> toGpgKeyMap(GpgKey key) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", key.getId());
                map.put("user_id", key.getUserId());
                map.put("armored_key", key.getArmoredKey());
                map.put("bits", key.getBits());
                map.put("uid", key.getUid());
                map.put("key_id", key.getKeyId());
                map.put("fingerprint", key.getFingerprint());
                map.put("type", key.getType());
                map.put("expires", key.getExpires());
                map.put("key_created", key.getKeyCreated());
                map.put("deleted", key.getDeleted());
                map.put("created", key.getCreated());
                map.put("modified", key.getModified());
                return map;
        }

        // ------------------------------------------------------------------
        // Security helpers
        // ------------------------------------------------------------------

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

        private boolean isCurrentUserAdmin() {
                return userService.isAdmin(getCurrentUserId());
        }

        private boolean isUuid(String value) {
                return value != null && UUID_PATTERN.matcher(value).matches();
        }

        // ------------------------------------------------------------------
        // Envelope helpers
        // ------------------------------------------------------------------

        private Map<String, Object> createResponse(String status, String message, Object body, String url) {
                // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留原 200/400 code 与 null→{} 语义
                // （header.code 维持既有约定，不改变现有 HTTP 状态码语义）。
                return ApiResponse.withCode(status, message, body, "success".equals(status) ? 200 : 400, url);
        }

        /**
         * nullBody envelope: body must be JSON null (OpenAPI
         * responses/nullBody, used by DELETE and dry-run success) — local
         * deviation from createResponse's empty {} fallback, do not
         * generalize.
         */
        private Map<String, Object> createNullBodyResponse(String status, String message, String url) {
                // 迁移到共享信封工具：补 action(uuid)，保留 body=null 的有意偏差。
                return ApiResponse.nullBody(status, message, url);
        }
}
