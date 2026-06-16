package com.jpassbolt.api.controller;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.dto.RecoverDto;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.service.RecoverService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public account-recovery endpoints (PHP UsersRecoverController /
 * RecoverStartController / RecoverCompleteController / RecoverAbortController).
 * Outside the OpenAPI spec domain — behaviour is aligned with the PHP
 * reference, exactly like {@link SetupController}.
 *
 * <p>
 * All endpoints are GUEST-only: an authenticated caller gets 403. They must be
 * whitelisted in SecurityConfig ("/setup/recover/**" + "/users/recover"
 * permitAll), since the recovering user is not yet (re)authenticated.
 * </p>
 *
 * <p>
 * No class-level @RequestMapping: with Boot 3's PathPatternParser a class-level
 * prefix combined with a method-level ".json" suffix yields the wrong path
 * (e.g. "/users/recover/.json"), so the plugin's suffixed URLs would 404. Full
 * method-level paths avoid that, mirroring UsersController.
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RecoverController {

    private final RecoverService recoverService;
    private final ProfileRepository profileRepository;
    private final RoleRepository roleRepository;
    private final SettingsProperties settingsProperties;

    /**
     * POST /users/recover.json
     * Enumeration-safe recovery request: always 200 with the same message,
     * whether or not the username maps to an eligible user (PHP
     * UsersRecoverController::recoverPost swallows the NotFoundException when
     * preventEmailEnumeration is on). Success body is JSON null (nullBody).
     */
    @PostMapping({ "/users/recover", "/users/recover.json" })
    public ResponseEntity<Map<String, Object>> recover(
            @RequestBody(required = false) RecoverDto.RecoverRequest request) {
        String url = "/users/recover.json";

        if (isAuthenticated()) {
            return ResponseEntity.status(403)
                    .body(createResponse("error",
                            "Only guests are allowed to recover an account. Please logout first.", null, url));
        }

        try {
            recoverService.recover(request);
            return ResponseEntity.ok(createNullBodyResponse("success",
                    "Recovery process started, check your email.", url));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400)
                    .body(createResponse("error", e.getMessage(), null, url));
        }
    }

    /**
     * GET /setup/recover/start/{userId}/{tokenId}.json
     * Read-only validation of the recovery link; returns the active user.
     */
    @GetMapping("/setup/recover/start/{userId}/{tokenId}.json")
    public ResponseEntity<Map<String, Object>> start(
            @PathVariable String userId,
            @PathVariable String tokenId) {
        String url = "/setup/recover/start/" + userId + "/" + tokenId + ".json";

        if (isAuthenticated()) {
            return ResponseEntity.status(403)
                    .body(createResponse("error",
                            "Only guests are allowed to proceed with account recovery.", null, url));
        }

        try {
            User user = recoverService.startRecover(userId, tokenId);
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
     * PUT|POST /setup/recover/complete/{userId}.json
     * Asserts the submitted public key already belongs to the active user and
     * consumes the recover token. Installs no key, changes no user state
     * (PHP RecoverCompleteService). Success body is JSON null (nullBody).
     */
    @RequestMapping(value = "/setup/recover/complete/{userId}.json", method = { RequestMethod.PUT,
            RequestMethod.POST })
    public ResponseEntity<Map<String, Object>> complete(
            @PathVariable String userId,
            @RequestBody(required = false) RecoverDto.CompleteRequest request) {
        String url = "/setup/recover/complete/" + userId + ".json";

        if (isAuthenticated()) {
            return ResponseEntity.status(403)
                    .body(createResponse("error",
                            "Only guests are allowed to complete an account recovery.", null, url));
        }

        try {
            recoverService.completeRecover(userId, request);
            return ResponseEntity.ok(
                    createNullBodyResponse("success", "The recovery was completed successfully.", url));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400)
                    .body(createResponse("error", e.getMessage(), null, url));
        }
    }

    /**
     * PUT|POST /setup/recover/abort/{userId}.json
     * Consume the recover token to abort an in-progress recovery (PHP
     * RecoverAbortService). Success body is JSON null (nullBody).
     */
    @RequestMapping(value = "/setup/recover/abort/{userId}.json", method = { RequestMethod.PUT,
            RequestMethod.POST })
    public ResponseEntity<Map<String, Object>> abort(
            @PathVariable String userId,
            @RequestBody(required = false) RecoverDto.AbortRequest request) {
        String url = "/setup/recover/abort/" + userId + ".json";

        if (isAuthenticated()) {
            return ResponseEntity.status(403)
                    .body(createResponse("error",
                            "Only guests are allowed to abort an account recovery.", null, url));
        }

        try {
            recoverService.abortRecover(userId, request);
            return ResponseEntity.ok(
                    createNullBodyResponse("success", "The operation was successful.", url));
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
     * Render the recovering user in userIndexAndView shape, mirroring
     * SetupController.toUserDetailMap. gpgkey is rendered null here too: the
     * recover-start view does not need the key (the plugin already holds it
     * locally), and keeping the shape identical to setup-start avoids a second
     * gpgkey lookup.
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
     * Passbolt envelope with body passthrough (null body stays JSON null),
     * same contract as SetupController.
     */
    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        return ApiResponse.passthrough(status, message, body, url);
    }

    /**
     * nullBody envelope: body fixed to JSON null (PHP success() with no data).
     */
    private Map<String, Object> createNullBodyResponse(String status, String message, String url) {
        return ApiResponse.nullBody(status, message, url);
    }
}
