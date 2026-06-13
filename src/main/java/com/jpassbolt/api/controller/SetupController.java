package com.jpassbolt.api.controller;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.dto.SetupDto;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.service.SetupService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public account-setup endpoints (PHP SetupStartController /
 * SetupCompleteController). Outside the OpenAPI spec domain — behaviour is
 * aligned with the PHP reference.
 *
 * <p>
 * Both endpoints are GUEST-only: an authenticated caller gets 403. They must
 * be whitelisted in SecurityConfig ("/setup/**" permitAll), since the user
 * being activated cannot have a JWT yet.
 * </p>
 *
 * <p>
 * PHP registers complete under PUT|POST (routes.php L334) — mirrored with
 * {@code @RequestMapping(method = {PUT, POST})}. The legacy alias
 * /users/validateAccount/{userId} (pre-v3 plugins) is deliberately NOT
 * registered: it would collide with UsersController's /users/{id} path
 * variable. NOTE: this is a closed decision, not a pending task — the
 * supported v3/v4 plugins use /setup/complete, so the legacy alias is out of
 * scope. Revisit only if pre-v3 plugin compatibility ever becomes a goal.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SetupService setupService;
    private final ProfileRepository profileRepository;
    private final RoleRepository roleRepository;
    private final SettingsProperties settingsProperties;

    /**
     * GET /setup/start/{userId}/{tokenId}.json
     * Read-only validation of the setup link; returns the pending user.
     */
    @GetMapping("/start/{userId}/{tokenId}.json")
    public ResponseEntity<Map<String, Object>> start(
            @PathVariable String userId,
            @PathVariable String tokenId) {
        String url = "/setup/start/" + userId + "/" + tokenId + ".json";

        if (isAuthenticated()) {
            return ResponseEntity.status(403)
                    .body(createResponse("error", "Only guests are allowed to start setup.", null, url));
        }

        try {
            User user = setupService.startSetup(userId, tokenId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user", toUserDetailMap(user));
            return ResponseEntity.ok(
                    createResponse("success", "The operation was successful.", body, url));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400)
                    .body(createResponse("error", e.getMessage(), null, url));
        }
    }

    /**
     * PUT|POST /setup/complete/{userId}.json
     * Uploads the user's OpenPGP public key, activates the account and
     * consumes the register token. Success body is JSON null (nullBody).
     */
    @RequestMapping(value = "/complete/{userId}.json", method = { RequestMethod.PUT, RequestMethod.POST })
    public ResponseEntity<Map<String, Object>> complete(
            @PathVariable String userId,
            @RequestBody(required = false) SetupDto.CompleteRequest request) {
        String url = "/setup/complete/" + userId + ".json";

        if (isAuthenticated()) {
            return ResponseEntity.status(403)
                    .body(createResponse("error", "Only guests are allowed to complete setup.", null, url));
        }

        try {
            setupService.completeSetup(userId, request);
            return ResponseEntity.ok(
                    createResponse("success", "The setup was completed successfully.", null, url));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400)
                    .body(createResponse("error", e.getMessage(), null, url));
        }
    }

    /**
     * True when a real principal is present (anonymous does not count).
     */
    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }

    /**
     * Render the pending user in userIndexAndView shape. gpgkey is always
     * null here — a pending user has not uploaded a key yet by definition.
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
        map.put("groups_users", List.of());
        map.put("profile", profileRepository.findByUserId(user.getId())
                .map(this::toProfileMap).orElse(null));
        map.put("role", roleRepository.findById(user.getRoleId())
                .map(this::toRoleMap).orElse(null));
        map.put("gpgkey", null);
        return map;
    }

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

    /**
     * Passbolt envelope. Local deviation from the project-wide helper: a
     * null body stays JSON null (nullBody contract — the official plugin
     * expects body:null from setup/complete), instead of the empty {}
     * fallback used elsewhere. Do not propagate this change to other
     * controllers.
     */
    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留 body 透传（不回退 {}）特例。
        return ApiResponse.passthrough(status, message, body, url);
    }
}
