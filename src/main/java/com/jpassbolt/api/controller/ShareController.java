package com.jpassbolt.api.controller;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.dto.ShareDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.exception.ShareValidationException;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.PermissionService;
import com.jpassbolt.api.service.ShareSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ShareController provides the Passbolt share endpoints:
 *
 * <ul>
 * <li>GET /share/search-aros.json — search users/groups to share with</li>
 * <li>PUT /share/{foreignModel}/{foreignId}.json — apply permission and
 * secret changes (foreignModel: "resource"; "folder" pending the folders
 * cluster)</li>
 * <li>POST /share/simulate/{foreignModel}/{foreignId}.json — dry run, returns
 * the users who would gain/lose access</li>
 * </ul>
 *
 * The former GET /share/resource/{id}/permissions endpoint moved to the
 * standard PermissionsController (GET /permissions/resource/{id}.json).
 */
@Slf4j
@RestController
@RequestMapping("/share")
@RequiredArgsConstructor
public class ShareController {

        private static final Pattern UUID_PATTERN = Pattern.compile(
                        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

        private final PermissionService permissionService;
        private final ShareSearchService shareSearchService;
        private final UserRepository userRepository;
        private final SettingsProperties settingsProperties;

        /**
         * GET /share/search-aros.json
         * Search the users and groups a resource could be shared with
         * (PHP ShareSearchController::searchArosToShareWith). Any
         * authenticated user may call it; the result set is intrinsically
         * limited to active, non-deleted, non-guest users and non-deleted
         * groups, each model capped at 25 rows, merged and sorted
         * alphabetically (case-insensitive).
         *
         * Contain semantics (PHP QueryString isset): when none of the three
         * whitelisted contain flags is present, ALL of them default to on;
         * as soon as one is present, only the requested ones are rendered
         * (a contain parameter present with value 0 still counts as
         * requested — isset semantics). Profile is not part of the whitelist
         * and is always rendered.
         */
        @GetMapping({ "/search-aros", "/search-aros.json" })
        public ResponseEntity<Map<String, Object>> searchAros(
                        @RequestParam(name = "filter[search]", required = false) String search,
                        @RequestParam(name = "contain[groups_users]", required = false) Integer containGroupsUsers,
                        @RequestParam(name = "contain[gpgkey]", required = false) Integer containGpgkey,
                        @RequestParam(name = "contain[role]", required = false) Integer containRole) {
                String url = "/share/search-aros.json";
                getCurrentUserId(); // authentication guard only — no object-level check (PHP parity)

                boolean anyContain = containGroupsUsers != null || containGpgkey != null || containRole != null;
                boolean withGroupsUsers = !anyContain || containGroupsUsers != null;
                boolean withGpgkey = !anyContain || containGpgkey != null;
                boolean withRole = !anyContain || containRole != null;

                ShareSearchService.AroSearchResult result = shareSearchService.searchAros(
                                search, withGpgkey, withRole, withGroupsUsers);

                // Merge users and groups, then sort the union alphabetically by
                // lowercase username/name (PHP _formatResult sortBy SORT_STRING ASC).
                List<SortableAro> aros = new ArrayList<>();
                for (User user : result.users) {
                        aros.add(new SortableAro(lower(user.getUsername()),
                                        toAroUserMap(user, result, withGroupsUsers, withGpgkey, withRole)));
                }
                for (Group group : result.groups) {
                        aros.add(new SortableAro(lower(group.getName()), toAroGroupMap(group, result)));
                }
                aros.sort(java.util.Comparator.comparing(SortableAro::sortKey));
                List<Map<String, Object>> body = aros.stream()
                                .map(SortableAro::value)
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                body, url));
        }

        /**
         * PUT /share/{foreignModel}/{foreignId}.json
         * Share a resource: apply permission changes and provide encrypted
         * secrets for the users who gain access. Success body is JSON null
         * (OpenAPI responses/nullBody).
         */
        @PutMapping({ "/{foreignModel}/{foreignId}", "/{foreignModel}/{foreignId}.json" })
        public ResponseEntity<Map<String, Object>> share(
                        @PathVariable String foreignModel,
                        @PathVariable String foreignId,
                        @RequestBody ShareDto.ShareRequest request) {
                String url = "/share/" + foreignModel + "/" + foreignId + ".json";
                String userId = getCurrentUserId();

                ResponseEntity<Map<String, Object>> guard = validateForeignParameters(foreignModel, foreignId, url);
                if (guard != null) {
                        return guard;
                }

                try {
                        List<ShareDto.PermissionChange> permissions = request != null
                                        && request.getPermissions() != null
                                                        ? request.getPermissions()
                                                        : List.of();
                        List<ShareDto.SecretAdd> secrets = request != null ? request.getSecrets() : null;

                        permissionService.share(foreignId, userId, permissions, secrets);

                        // nullBody contract: the spec and PHP both emit "body": null —
                        // deliberate deviation from the shared createResponse fallback
                        // (empty {}), do NOT change createResponse itself.
                        return ResponseEntity.ok(createNullBodyResponse("success",
                                        "The operation was successful.", url));
                } catch (ShareValidationException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), e.getErrors(), url));
                } catch (SecurityException e) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", e.getMessage(), null, url));
                } catch (PassboltApiException e) {
                        // e.g. 404 "The resource does not exist." — pass the status through
                        return ResponseEntity.status(e.getStatus())
                                        .body(createResponse("error", e.getMessage(), null, url));
                }
        }

        /**
         * POST /share/simulate/{foreignModel}/{foreignId}.json
         * Simulate sharing (dry run): full validation, never persists.
         * Returns which users would be added/removed.
         */
        @PostMapping({ "/simulate/{foreignModel}/{foreignId}", "/simulate/{foreignModel}/{foreignId}.json" })
        public ResponseEntity<Map<String, Object>> simulate(
                        @PathVariable String foreignModel,
                        @PathVariable String foreignId,
                        @RequestBody ShareDto.ShareRequest request) {
                String url = "/share/simulate/" + foreignModel + "/" + foreignId + ".json";
                String userId = getCurrentUserId();

                ResponseEntity<Map<String, Object>> guard = validateForeignParameters(foreignModel, foreignId, url);
                if (guard != null) {
                        return guard;
                }

                try {
                        List<ShareDto.PermissionChange> permissions = request != null
                                        && request.getPermissions() != null
                                                        ? request.getPermissions()
                                                        : List.of();

                        Map<String, List<String>> dryRunResult = permissionService.shareDryRun(
                                        foreignId, userId, permissions);

                        // {changes: {added: [{User: {id}}], removed: [{User: {id}}]}} —
                        // the OpenAPI schema (shareUpdateDryRun) contradicts its own
                        // example here; PHP and the official plugin both use the
                        // "changes" wrapper, so we keep it (do NOT flatten to pass the
                        // contract check — that would break plugin compatibility).
                        Map<String, Object> changes = new LinkedHashMap<>();
                        changes.put("added", toUserIdEntries(dryRunResult.get("added")));
                        changes.put("removed", toUserIdEntries(dryRunResult.get("deleted")));

                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("changes", changes);

                        return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                        body, url));
                } catch (ShareValidationException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), e.getErrors(), url));
                } catch (SecurityException e) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", e.getMessage(), null, url));
                } catch (PassboltApiException e) {
                        return ResponseEntity.status(e.getStatus())
                                        .body(createResponse("error", e.getMessage(), null, url));
                }
        }

        // ------------------------------------------------------------------
        // Guards
        // ------------------------------------------------------------------

        /**
         * Shared foreignModel/foreignId precondition checks. Order matters:
         * the model whitelist runs BEFORE the UUID check (PUT /share/simulate/x
         * falls into foreignModel="simulate" and must 404, not 400).
         */
        private ResponseEntity<Map<String, Object>> validateForeignParameters(
                        String foreignModel, String foreignId, String url) {
                if ("folder".equals(foreignModel)) {
                        // TODO(folders): implement folder sharing when the folders
                        // cluster lands; until then a folder share target is a 404.
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The folder does not exist.", null, url));
                }
                if (!"resource".equals(foreignModel)) {
                        // PHP: no matching route → 404
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "Not Found", null, url));
                }
                if (!isUuid(foreignId)) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error",
                                                        "The resource identifier should be a valid UUID.", null, url));
                }
                return null;
        }

        // ------------------------------------------------------------------
        // Rendering helpers
        // ------------------------------------------------------------------

        /**
         * shareAros user element (OpenAPI userIndexAndView): base fields +
         * last_logged_in (not tracked yet) + profile (always, with the
         * mandatory default avatar URLs) + gpgkey/role/groups_users on demand.
         */
        private Map<String, Object> toAroUserMap(User user, ShareSearchService.AroSearchResult result,
                        boolean withGroupsUsers, boolean withGpgkey, boolean withRole) {
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
                Profile profile = result.profilesByUserId.get(user.getId());
                map.put("profile", profile != null ? toProfileMap(profile) : null);
                if (withGpgkey) {
                        GpgKey key = result.gpgkeysByUserId.get(user.getId());
                        map.put("gpgkey", key != null ? toGpgKeyMap(key) : null);
                }
                if (withRole) {
                        Role role = result.rolesById.get(user.getRoleId());
                        map.put("role", role != null ? toRoleMap(role) : null);
                }
                if (withGroupsUsers) {
                        List<GroupUser> memberships = result.groupsUsersByUserId
                                        .getOrDefault(user.getId(), List.of());
                        map.put("groups_users", memberships.stream()
                                        .map(this::toGroupUserMap)
                                        .collect(Collectors.toList()));
                }
                return map;
        }

        /**
         * shareAros group element (OpenAPI groupIndexAndView) + user_count.
         */
        private Map<String, Object> toAroGroupMap(Group group, ShareSearchService.AroSearchResult result) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", group.getId());
                map.put("name", group.getName());
                map.put("deleted", group.getDeleted());
                map.put("created", group.getCreated());
                map.put("modified", group.getModified());
                map.put("created_by", group.getCreatedBy());
                map.put("modified_by", group.getModifiedBy());
                map.put("user_count", result.userCountByGroupId.getOrDefault(group.getId(), 0));
                return map;
        }

        /**
         * profile.avatar is REQUIRED by the spec: always emit the default
         * placeholder URLs (same pattern as UsersController; real avatar
         * storage belongs to the avatars cluster).
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

        private Map<String, Object> toRoleMap(Role role) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", role.getId());
                map.put("name", role.getName());
                map.put("description", role.getDescription());
                map.put("created", role.getCreated());
                map.put("modified", role.getModified());
                return map;
        }

        private Map<String, Object> toGroupUserMap(GroupUser groupUser) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", groupUser.getId());
                map.put("group_id", groupUser.getGroupId());
                map.put("user_id", groupUser.getUserId());
                map.put("is_admin", groupUser.getIsAdmin());
                map.put("created", groupUser.getCreated());
                return map;
        }

        private static List<Map<String, Object>> toUserIdEntries(List<String> userIds) {
                List<String> safe = userIds != null ? userIds : List.of();
                return safe.stream()
                                .map(uid -> {
                                        Map<String, Object> entry = new LinkedHashMap<>();
                                        entry.put("User", Map.of("id", uid));
                                        return entry;
                                })
                                .collect(Collectors.toList());
        }

        private static String lower(String value) {
                return value == null ? "" : value.toLowerCase();
        }

        /** Merge-sort carrier: lowercase name key + rendered element. */
        private record SortableAro(String sortKey, Map<String, Object> value) {
        }

        // ------------------------------------------------------------------
        // Shared helpers (same pattern as ResourceController)
        // ------------------------------------------------------------------

        private boolean isUuid(String value) {
                return value != null && UUID_PATTERN.matcher(value).matches();
        }

        /**
         * Get the current authenticated user's ID.
         */
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

        /**
         * Create a Passbolt-style response body.
         */
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

        /**
         * nullBody envelope: body must be JSON null (OpenAPI
         * responses/nullBody, PHP success() emits "body": null) — local
         * deviation from createResponse's empty {} fallback, do not
         * generalize (same precedent as UsersController).
         */
        private Map<String, Object> createNullBodyResponse(String status, String message, String url) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("header", Map.of(
                                "id", UUID.randomUUID().toString(),
                                "status", status,
                                "servertime", System.currentTimeMillis() / 1000,
                                "code", "success".equals(status) ? 200 : 400,
                                "message", message,
                                "url", url));
                response.put("body", null);
                return response;
        }
}
