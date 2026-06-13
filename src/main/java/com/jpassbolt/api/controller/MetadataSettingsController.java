package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.MetadataSettingsDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.MetadataKeysSettingsService;
import com.jpassbolt.api.service.MetadataTypesSettingsService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v5 Metadata <b>settings</b> endpoints — the two organization-level toggle
 * groups that govern the metadata (zero-knowledge) subsystem:
 *
 * <ul>
 *   <li>{@code GET|POST /metadata/keys/settings.json} — the
 *       {@code allow_usage_of_personal_keys} / {@code zero_knowledge_key_share}
 *       pair (OpenAPI {@code metadataKeysSettingsIndex} /
 *       {@code metadataKeysSettingsUpdate}).</li>
 *   <li>{@code GET|POST /metadata/types/settings.json} — the 14-field
 *       v4/v5 content-type policy (OpenAPI
 *       {@code metadataTypesSettingsIndexAndView}).</li>
 * </ul>
 *
 * <p>
 * Both settings groups are stored as single {@code organization_settings} rows
 * (property = {@code metadataKeys} / {@code metadataTypes}, value = JSON) by the
 * settings services; they are NOT entity-backed. The GET endpoints return
 * sensible defaults when the row is absent (keys: personal = true, zero
 * knowledge = false; types: all {@code default_* = v4}, {@code allow_v5_* =
 * false}, {@code allow_v4_* = true}, downgrade/upgrade = false). The GET
 * endpoints are JWT-protected only (any authenticated user may read the policy);
 * the POST (write) endpoints are additionally admin-gated in-controller via
 * {@code userService.isAdmin(getCurrentUserId())}, mirroring the
 * {@code UsersController} precedent.
 * </p>
 *
 * <p>
 * Zero-knowledge note (iron law 1): this controller never touches OpenPGP
 * material. The optional {@code metadata_private_keys} array that the keys
 * settings POST may carry holds armored ciphertext persisted by the keys domain
 * — the settings layer only validates/persists the toggles themselves.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/metadata")
@RequiredArgsConstructor
public class MetadataSettingsController {

    private final MetadataKeysSettingsService metadataKeysSettingsService;
    private final MetadataTypesSettingsService metadataTypesSettingsService;
    private final UserService userService;
    private final UserRepository userRepository;

    // ---------------------------------------------------------------------
    // keys settings
    // ---------------------------------------------------------------------

    /**
     * GET /metadata/keys/settings.json
     * Returns the metadata keys settings (or sensible defaults when never set).
     * Readable by any authenticated user.
     */
    @GetMapping("/keys/settings.json")
    public ResponseEntity<Map<String, Object>> getKeysSettings() {
        String url = "/metadata/keys/settings.json";
        getCurrentUserId(); // 401/404 guard via PassboltApiException

        MetadataSettingsDto.KeysSettings settings = metadataKeysSettingsService.getKeysSettings();
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    /**
     * POST /metadata/keys/settings.json
     * Validates and persists the metadata keys settings. Admin only.
     */
    @PostMapping("/keys/settings.json")
    public ResponseEntity<Map<String, Object>> setKeysSettings(
            @RequestBody MetadataSettingsDto.KeysSettingsUpdate request) {
        String url = "/metadata/keys/settings.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        MetadataSettingsDto.KeysSettings settings =
                metadataKeysSettingsService.setKeysSettings(request, userId);
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    // ---------------------------------------------------------------------
    // types settings
    // ---------------------------------------------------------------------

    /**
     * GET /metadata/types/settings.json
     * Returns the metadata types settings (or v4 defaults when never set).
     * Readable by any authenticated user.
     */
    @GetMapping("/types/settings.json")
    public ResponseEntity<Map<String, Object>> getTypesSettings() {
        String url = "/metadata/types/settings.json";
        getCurrentUserId(); // 401/404 guard via PassboltApiException

        MetadataSettingsDto.TypesSettings settings = metadataTypesSettingsService.getTypesSettings();
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    /**
     * POST /metadata/types/settings.json
     * Validates and persists the 14-field metadata types policy. Admin only.
     */
    @PostMapping("/types/settings.json")
    public ResponseEntity<Map<String, Object>> setTypesSettings(
            @RequestBody MetadataSettingsDto.TypesSettings request) {
        String url = "/metadata/types/settings.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        MetadataSettingsDto.TypesSettings settings =
                metadataTypesSettingsService.setTypesSettings(request, userId);
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    // ---------------------------------------------------------------------
    // security helper (same pattern as CommentController / UsersController)
    // ---------------------------------------------------------------------

    /**
     * Admin gate (PHP {@code accessRestrictedToAdministrators}). Returns a 403
     * response entity when the current user is not an admin, otherwise null.
     *
     * <p>
     * The body is an EMPTY STRING (not {@code null}) so the envelope matches the
     * spec's {@code accessRestrictedToAdministrators} response, whose
     * {@code body} is {@code type: string} (example {@code body: ''}). Passing
     * {@code null} would let {@link ApiResponse} fall back to an empty object
     * {@code {}}, which violates that {@code type: string}. This mirrors
     * {@code MetadataKeyController.requireAdmin} for cross-controller
     * consistency among the admin-gated v5 endpoints.
     * </p>
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
