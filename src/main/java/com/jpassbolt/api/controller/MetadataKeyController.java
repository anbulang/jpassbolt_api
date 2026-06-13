package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.MetadataKeyDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.MetadataPrivateKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.MetadataKeyService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * REST endpoints for the v5 (zero-knowledge) Metadata Keys domain:
 * {@code /metadata/keys*}.
 *
 * <p>
 * The server is ZERO-KNOWLEDGE for v5 — it only STORES and FORWARDS the armored
 * OpenPGP blobs (the public {@code armored_key} of a metadata key and each
 * user's encrypted copy of the matching private key {@code data}). It never
 * decrypts; only the parse-only structural validations carried out in the
 * service layer apply. See {@link MetadataKeyService} for the business rules
 * ported from the official Passbolt PHP plugin.
 * </p>
 *
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET  /metadata/keys.json} — list metadata keys
 *       (filter[deleted]/filter[expired]/contain[metadata_private_keys]);</li>
 *   <li>{@code POST /metadata/keys.json} — create a metadata key + private keys
 *       (admin);</li>
 *   <li>{@code PUT  /metadata/keys/{metadataKeyId}.json} — mark a key expired
 *       (admin);</li>
 *   <li>{@code DELETE /metadata/keys/{metadataKeyId}.json} — soft-delete a key
 *       (admin);</li>
 *   <li>{@code POST /metadata/keys/privates.json} — create/share missing
 *       per-user private keys (admin, array body);</li>
 *   <li>{@code PUT  /metadata/keys/private/{metadataPrivateKeyId}.json} —
 *       update a private key's encrypted data blob.</li>
 * </ul>
 * Admin-only endpoints are gated in-controller via
 * {@code userService.isAdmin(getCurrentUserId())} → 403
 * {@code accessRestrictedToAdministrators} (mirrors the UsersController
 * pattern; SecurityConfig already JWT-protects every {@code /metadata/**}
 * route via {@code anyRequest().authenticated()}).
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/metadata")
@RequiredArgsConstructor
public class MetadataKeyController {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final MetadataKeyService metadataKeyService;
    private final UserService userService;
    private final UserRepository userRepository;

    // ------------------------------------------------------------------
    // GET /metadata/keys.json — index
    // ------------------------------------------------------------------

    /**
     * List metadata keys. Open to any authenticated user (PHP
     * MetadataKeysIndexController has no admin gate — clients need the active
     * keys to encrypt new metadata).
     *
     * @param filterDeleted  optional {@code filter[deleted]} (null = all)
     * @param filterExpired  optional {@code filter[expired]} (null = all)
     * @param containPrivates optional {@code contain[metadata_private_keys]}:
     *                        when truthy, embed the requesting user's encrypted
     *                        private-key copies on each returned key
     */
    @GetMapping("/keys.json")
    public ResponseEntity<Map<String, Object>> index(
            @RequestParam(name = "filter[deleted]", required = false) Boolean filterDeleted,
            @RequestParam(name = "filter[expired]", required = false) Boolean filterExpired,
            @RequestParam(name = "contain[metadata_private_keys]", required = false) String containPrivates) {
        String url = "/metadata/keys.json";
        String userId = getCurrentUserId();

        boolean contain = isTruthy(containPrivates);
        List<MetadataKey> keys = metadataKeyService.findKeys(filterDeleted, filterExpired, contain, userId);

        // Scope the embedded private-key copies to the requesting user (PHP
        // contain closure MetadataPrivateKeys.user_id = $userId).
        final Map<String, List<MetadataPrivateKey>> privatesByKey;
        if (contain && !keys.isEmpty()) {
            List<String> keyIds = keys.stream().map(MetadataKey::getId).collect(Collectors.toList());
            privatesByKey = metadataKeyService.findUserPrivateKeysForKeys(keyIds, userId).stream()
                    .collect(Collectors.groupingBy(MetadataPrivateKey::getMetadataKeyId));
        } else {
            privatesByKey = Map.of();
        }

        List<MetadataKeyDto.Response> body = keys.stream()
                .map(key -> toResponse(key,
                        contain ? privatesByKey.getOrDefault(key.getId(), List.of()) : null))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", body, url));
    }

    // ------------------------------------------------------------------
    // POST /metadata/keys.json — create (admin)
    // ------------------------------------------------------------------

    /**
     * Create a metadata key together with its per-user encrypted private-key
     * copies. Admin only.
     */
    @PostMapping("/keys.json")
    public ResponseEntity<Map<String, Object>> create(@RequestBody MetadataKeyDto.CreateRequest request) {
        String url = "/metadata/keys.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        MetadataKeyService.CreateResult result = metadataKeyService.createKey(request, userId);

        // Echo back ONLY the private-key copies persisted by THIS create (PHP
        // MetadataKeyCreateService returns the saved entity graph with its
        // associated metadata_private_keys — NOT an indiscriminate re-query of
        // every copy of the key, which for an already-shared key would leak
        // unrelated users' encrypted blobs into the create response).
        MetadataKeyDto.Response body = toResponse(result.key(), result.privateKeys());
        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", List.of(body), url));
    }

    // ------------------------------------------------------------------
    // PUT /metadata/keys/{metadataKeyId}.json — mark expired (admin)
    // ------------------------------------------------------------------

    /**
     * Mark a metadata key as expired. Admin only. The PUT endpoint's only
     * function is to set the {@code expired} timestamp.
     */
    @PutMapping("/keys/{metadataKeyId}.json")
    public ResponseEntity<Map<String, Object>> expire(
            @PathVariable String metadataKeyId,
            @RequestBody MetadataKeyDto.ExpireRequest request) {
        String url = "/metadata/keys/" + metadataKeyId + ".json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }
        if (!isUuid(metadataKeyId)) {
            return ResponseEntity.status(400).body(ApiResponse.error(
                    "The metadata key ID should be a valid UUID.", null, url));
        }

        metadataKeyService.markExpired(metadataKeyId,
                request != null ? request.getFingerprint() : null,
                request != null ? request.getArmoredKey() : null,
                request != null ? request.getExpired() : null,
                userId);

        return ResponseEntity.ok(ApiResponse.nullBody("success", "The operation was successful.", url));
    }

    // ------------------------------------------------------------------
    // DELETE /metadata/keys/{metadataKeyId}.json — soft delete (admin)
    // ------------------------------------------------------------------

    /**
     * Soft-delete a metadata key (and remove its private-key copies). Admin
     * only. The key must already be expired and must not still be in use.
     */
    @DeleteMapping("/keys/{metadataKeyId}.json")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String metadataKeyId) {
        String url = "/metadata/keys/" + metadataKeyId + ".json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }
        if (!isUuid(metadataKeyId)) {
            return ResponseEntity.status(400).body(ApiResponse.error(
                    "The metadata key ID should be a valid UUID.", null, url));
        }

        metadataKeyService.deleteKey(metadataKeyId, userId);
        return ResponseEntity.ok(ApiResponse.nullBody("success", "The operation was successful.", url));
    }

    // ------------------------------------------------------------------
    // POST /metadata/keys/privates.json — create / share missing (admin)
    // ------------------------------------------------------------------

    /**
     * Create (share) one or more missing per-user private-key copies. Admin
     * only. The request body is an array of
     * {@code (metadata_key_id, user_id, data)} entries; all are validated
     * before any is persisted. Returns an empty object body
     * (OpenAPI {@code emptyBody}).
     */
    @PostMapping("/keys/privates.json")
    public ResponseEntity<Map<String, Object>> createPrivates(
            @RequestBody List<MetadataKeyDto.CreatePrivatesRequest> request) {
        String url = "/metadata/keys/privates.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        metadataKeyService.createPrivateKeys(request, userId);
        // emptyBody: body is {} (ApiResponse.success maps a null body to {}).
        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", null, url));
    }

    // ------------------------------------------------------------------
    // PUT /metadata/keys/private/{metadataPrivateKeyId}.json — update data
    // ------------------------------------------------------------------

    /**
     * Update the encrypted data blob of one of the current user's private-key
     * copies. Owner-scoped (foreign / missing → 404). Returns the short
     * private-key view (OpenAPI {@code metadataPrivateKeysShortIndex}).
     */
    @PutMapping("/keys/private/{metadataPrivateKeyId}.json")
    public ResponseEntity<Map<String, Object>> updatePrivate(
            @PathVariable String metadataPrivateKeyId,
            @RequestBody MetadataKeyDto.UpdatePrivateRequest request) {
        String url = "/metadata/keys/private/" + metadataPrivateKeyId + ".json";
        String userId = getCurrentUserId();

        MetadataPrivateKey updated = metadataKeyService.updatePrivateKey(metadataPrivateKeyId,
                request != null ? request.getData() : null, userId);

        MetadataKeyDto.PrivateKeyShortResponse body = MetadataKeyDto.PrivateKeyShortResponse.builder()
                .userId(updated.getUserId())
                .data(updated.getData())
                .createdBy(updated.getCreatedBy())
                .modifiedBy(updated.getModifiedBy())
                .build();

        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", body, url));
    }

    // ------------------------------------------------------------------
    // Rendering helpers
    // ------------------------------------------------------------------

    /**
     * Build the metadataKeysIndexAndView shape. When {@code privates} is null
     * the {@code metadata_private_keys} embed is omitted (it is
     * {@code @JsonInclude(NON_NULL)}); when present (contain requested) it is
     * rendered even if empty.
     */
    private MetadataKeyDto.Response toResponse(MetadataKey key, List<MetadataPrivateKey> privates) {
        List<MetadataKeyDto.PrivateKeyResponse> embed = privates == null ? null
                : privates.stream().map(this::toPrivateKeyResponse).collect(Collectors.toList());

        return MetadataKeyDto.Response.builder()
                .id(key.getId())
                .fingerprint(key.getFingerprint())
                .armoredKey(key.getArmoredKey())
                .created(key.getCreated())
                .modified(key.getModified())
                .expired(key.getExpired())
                .deleted(key.getDeleted())
                .createdBy(key.getCreatedBy())
                .modifiedBy(key.getModifiedBy())
                .metadataPrivateKeys(embed)
                .build();
    }

    private MetadataKeyDto.PrivateKeyResponse toPrivateKeyResponse(MetadataPrivateKey pk) {
        return MetadataKeyDto.PrivateKeyResponse.builder()
                .id(pk.getId())
                .metadataKeyId(pk.getMetadataKeyId())
                .userId(pk.getUserId())
                .data(pk.getData())
                .created(pk.getCreated())
                .modified(pk.getModified())
                .createdBy(pk.getCreatedBy())
                .modifiedBy(pk.getModifiedBy())
                .build();
    }

    // ------------------------------------------------------------------
    // Security helpers
    // ------------------------------------------------------------------

    /**
     * Admin gate (PHP {@code accessRestrictedToAdministrators}). Returns a 403
     * response entity when the current user is not an admin, otherwise null.
     */
    private ResponseEntity<Map<String, Object>> requireAdmin(String url) {
        if (!userService.isAdmin(getCurrentUserId())) {
            return ResponseEntity.status(403).body(ApiResponse.withCode("error",
                    "Access restricted to administrators.", "", 403, url));
        }
        return null;
    }

    private boolean isTruthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

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
}
