package com.jpassbolt.api.controller;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PermissionsController — the standard permissions listing endpoint
 * GET /permissions/resource/{resourceId}.json, ported from the PHP
 * PermissionsViewController::viewAcoPermissions.
 *
 * <p>
 * Validation order (PHP parity): ① non-UUID id → 400; ② resource missing,
 * soft-deleted OR not accessible to the caller → 404 "The resource does not
 * exist." — note: NO 403 here even when the resource exists, to avoid
 * leaking resource existence (deliberate deviation from the
 * ResourceController 403-guard pattern); ③ return ALL permission rows of the
 * ACO (including Group rows and other users' rows).
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionsController {

        private static final Pattern UUID_PATTERN = Pattern.compile(
                        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

        private final PermissionService permissionService;
        private final ResourceRepository resourceRepository;
        private final UserRepository userRepository;
        private final ProfileRepository profileRepository;
        private final GroupRepository groupRepository;
        private final SettingsProperties settingsProperties;

        /**
         * GET /permissions/resource/{resourceId}.json
         *
         * Contain semantics: PHP findViewAcoPermissions only does isset()
         * checks, so a contain parameter PRESENT with any value (even 0)
         * activates the association; absent parameters stay off.
         * contain[user.profile] implies the user association.
         */
        @GetMapping({ "/resource/{resourceId}", "/resource/{resourceId}.json" })
        public ResponseEntity<Map<String, Object>> viewAcoPermissions(
                        @PathVariable String resourceId,
                        @RequestParam(name = "contain[group]", required = false) Integer containGroup,
                        @RequestParam(name = "contain[user]", required = false) Integer containUser,
                        @RequestParam(name = "contain[user.profile]", required = false) Integer containUserProfile) {
                String url = "/permissions/resource/" + resourceId + ".json";
                String userId = getCurrentUserId();

                // ① UUID validation
                if (!isUuid(resourceId)) {
                        return ResponseEntity.status(400).body(createResponse("error",
                                        "The identifier should be a valid UUID.", null, url));
                }

                // ② findView semantics: missing, soft-deleted and inaccessible
                // resources are indistinguishable — all 404 (never 403).
                boolean exists = resourceRepository.findById(resourceId)
                                .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                                .isPresent();
                if (!exists || !permissionService.hasAccessIncludingGroups(resourceId, userId, Permission.READ)) {
                        return ResponseEntity.status(404).body(createResponse("error",
                                        "The resource does not exist.", null, url));
                }

                // ③ all permission rows of the ACO
                boolean withGroup = containGroup != null;
                boolean withUser = containUser != null || containUserProfile != null;
                boolean withUserProfile = containUserProfile != null;

                List<Map<String, Object>> body = permissionService.getResourcePermissions(resourceId).stream()
                                .map(p -> toPermissionMap(p, withGroup, withUser, withUserProfile))
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                body, url));
        }

        // ------------------------------------------------------------------
        // Rendering helpers
        // ------------------------------------------------------------------

        /**
         * permissionIndexAndView element: the 8 base fields, plus the user
         * association on "User" rows (Group rows never embed user) and the
         * group association on "Group" rows when requested.
         */
        private Map<String, Object> toPermissionMap(Permission permission, boolean withGroup,
                        boolean withUser, boolean withUserProfile) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", permission.getId());
                map.put("aco", permission.getAco());
                map.put("aco_foreign_key", permission.getAcoForeignKey());
                map.put("aro", permission.getAro());
                map.put("aro_foreign_key", permission.getAroForeignKey());
                map.put("type", permission.getType());
                map.put("created", permission.getCreated());
                map.put("modified", permission.getModified());

                if (withUser && Permission.USER_ARO.equals(permission.getAro())) {
                        map.put("user", userRepository.findById(permission.getAroForeignKey())
                                        .map(user -> toUserMap(user, withUserProfile))
                                        .orElse(null));
                }
                if (withGroup && Permission.GROUP_ARO.equals(permission.getAro())) {
                        map.put("group", groupRepository.findById(permission.getAroForeignKey())
                                        .map(this::toGroupMap)
                                        .orElse(null));
                }
                return map;
        }

        private Map<String, Object> toUserMap(User user, boolean withProfile) {
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
                if (withProfile) {
                        map.put("profile", profileRepository.findByUserId(user.getId())
                                        .map(this::toProfileMap)
                                        .orElse(null));
                }
                return map;
        }

        private Map<String, Object> toGroupMap(Group group) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", group.getId());
                map.put("name", group.getName());
                map.put("deleted", group.getDeleted());
                map.put("created", group.getCreated());
                map.put("modified", group.getModified());
                map.put("created_by", group.getCreatedBy());
                map.put("modified_by", group.getModifiedBy());
                return map;
        }

        /**
         * profile.avatar is REQUIRED by the spec: default placeholder URLs
         * (same pattern as UsersController / ShareController).
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
                Map<String, Object> urlMap = new LinkedHashMap<>();
                urlMap.put("medium", base + "/img/avatar/user_medium.png");
                urlMap.put("small", base + "/img/avatar/user.png");
                Map<String, Object> avatar = new LinkedHashMap<>();
                avatar.put("url", urlMap);
                map.put("avatar", avatar);
                return map;
        }

        // ------------------------------------------------------------------
        // Shared helpers (same pattern as ShareController)
        // ------------------------------------------------------------------

        private boolean isUuid(String value) {
                return value != null && UUID_PATTERN.matcher(value).matches();
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

        private Map<String, Object> createResponse(String status, String message, Object body, String url) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("header", Map.of(
                                "id", UUID.randomUUID().toString(),
                                "status", status,
                                "servertime", System.currentTimeMillis() / 1000,
                                "code", "success".equals(status) ? 200 : 400,
                                "message", message,
                                "url", url));
                response.put("body", body != null ? body : new LinkedHashMap<>());
                return response;
        }
}
