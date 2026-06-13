package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.MetadataRotateKeyDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Tag;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.MetadataRotateKeyService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST endpoints for the v5 Metadata <b>rotate-key</b> domain:
 * {@code /metadata/rotate-key/{resources,folders,tags}.json}.
 *
 * <p>
 * Once a shared metadata key is expired (or deleted) every v5 entity still
 * referencing it must have its metadata re-encrypted under a fresh active key.
 * Each model exposes:
 * <ul>
 *   <li>{@code GET} — paginated index of the entities whose current (shared)
 *       metadata key is no longer active and therefore need re-keying;</li>
 *   <li>{@code POST} — apply the rotation for an array of posted elements
 *       (re-write {@code metadata} + the new {@code metadata_key_id}). The new key
 *       must be an active {@code shared_key}; a violation yields the
 *       {@code tooManyUpdatedEntities} 409.</li>
 * </ul>
 * The responses render the v5 metadata-based projections
 * ({@code resourceMetadataRotateKey} for resources — adds {@code resource_type_id},
 * {@code e2eeMetadataBasedCommon} for folders, {@code tagV5MetadataRotateKey} for
 * tags) wrapped in the {@code headerWithPagination} envelope.
 * </p>
 *
 * <h3>Security</h3>
 * <p>
 * JWT-protected by {@code SecurityConfig}; additionally admin-only, gated
 * in-controller with a 403 {@code accessRestrictedToAdministrators} (mirrors the
 * {@code MetadataKeyController} pattern). The rotate-key domain reuses
 * {@link MetadataRotateKeyService}, which injects the keys-domain
 * {@code MetadataKeyService}/{@code MetadataKeyRepository} read-only to resolve
 * active vs expired keys.
 * </p>
 *
 * <h3>Iron Laws</h3>
 * <p>
 * Zero-knowledge (#1): the re-encrypted {@code metadata} payload is a client
 * armored OpenPGP MESSAGE; this controller and the service only store/forward it
 * — never decrypt. DTOs are transport-only (#3); all rules and the conflict
 * detection live in the service.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/metadata")
@RequiredArgsConstructor
public class MetadataRotateKeyController {

    /** Default page size for the rotate-key index (PHP metadata pagination limit). */
    private static final int DEFAULT_PAGE_SIZE = MetadataRotateKeyService.DEFAULT_PAGE_SIZE;

    private final MetadataRotateKeyService metadataRotateKeyService;
    private final UserService userService;
    private final UserRepository userRepository;

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    /**
     * {@code GET /metadata/rotate-key/resources.json} — paginated index of
     * resources whose current shared metadata key is expired/deleted. Admin only.
     */
    @GetMapping("/rotate-key/resources.json")
    public ResponseEntity<Map<String, Object>> indexResourcesToRotate(
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        String url = "/metadata/rotate-key/resources.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        List<Resource> resources = metadataRotateKeyService.findResourcesWithExpiredKeys(page, limit);
        List<Map<String, Object>> body = resources.stream()
                .map(this::toResourceRotateView)
                .collect(Collectors.toList());
        return paginated(body, page, limit, url);
    }

    /**
     * {@code POST /metadata/rotate-key/resources.json} — re-key an array of
     * resources under a fresh active shared key. Admin only.
     */
    @PostMapping("/rotate-key/resources.json")
    public ResponseEntity<Map<String, Object>> rotateResources(
            @RequestBody List<MetadataRotateKeyDto.RotateRequest> request) {
        String url = "/metadata/rotate-key/resources.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        metadataRotateKeyService.rotateResources(request, getCurrentUserId());

        List<Map<String, Object>> body = metadataRotateKeyService
                .findResourcesWithExpiredKeys(0, DEFAULT_PAGE_SIZE).stream()
                .map(this::toResourceRotateView).collect(Collectors.toList());
        return paginated(body, 0, DEFAULT_PAGE_SIZE, url);
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    /**
     * {@code GET /metadata/rotate-key/folders.json} — paginated index of folders
     * whose current shared metadata key is expired/deleted. Admin only.
     */
    @GetMapping("/rotate-key/folders.json")
    public ResponseEntity<Map<String, Object>> indexFoldersToRotate(
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        String url = "/metadata/rotate-key/folders.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        List<Folder> folders = metadataRotateKeyService.findFoldersWithExpiredKeys(page, limit);
        List<Map<String, Object>> body = folders.stream()
                .map(this::toFolderRotateView)
                .collect(Collectors.toList());
        return paginated(body, page, limit, url);
    }

    /**
     * {@code POST /metadata/rotate-key/folders.json} — re-key an array of folders.
     * Admin only.
     */
    @PostMapping("/rotate-key/folders.json")
    public ResponseEntity<Map<String, Object>> rotateFolders(
            @RequestBody List<MetadataRotateKeyDto.RotateRequest> request) {
        String url = "/metadata/rotate-key/folders.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        metadataRotateKeyService.rotateFolders(request, getCurrentUserId());

        List<Map<String, Object>> body = metadataRotateKeyService
                .findFoldersWithExpiredKeys(0, DEFAULT_PAGE_SIZE).stream()
                .map(this::toFolderRotateView).collect(Collectors.toList());
        return paginated(body, 0, DEFAULT_PAGE_SIZE, url);
    }

    // ------------------------------------------------------------------
    // Tags (v5.1)
    // ------------------------------------------------------------------

    /**
     * {@code GET /metadata/rotate-key/tags.json} — paginated index of tags whose
     * current shared metadata key is expired/deleted (v5.1). Admin only.
     */
    @GetMapping("/rotate-key/tags.json")
    public ResponseEntity<Map<String, Object>> indexTagsToRotate(
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        String url = "/metadata/rotate-key/tags.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        List<Tag> tags = metadataRotateKeyService.findTagsWithExpiredKeys(page, limit);
        List<Map<String, Object>> body = tags.stream()
                .map(this::toTagRotateView)
                .collect(Collectors.toList());
        return paginated(body, page, limit, url);
    }

    /**
     * {@code POST /metadata/rotate-key/tags.json} — re-key an array of tags
     * (v5.1). Admin only.
     */
    @PostMapping("/rotate-key/tags.json")
    public ResponseEntity<Map<String, Object>> rotateTags(
            @RequestBody List<MetadataRotateKeyDto.RotateRequest> request) {
        String url = "/metadata/rotate-key/tags.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        metadataRotateKeyService.rotateTags(request, getCurrentUserId());

        List<Map<String, Object>> body = metadataRotateKeyService
                .findTagsWithExpiredKeys(0, DEFAULT_PAGE_SIZE).stream()
                .map(this::toTagRotateView).collect(Collectors.toList());
        return paginated(body, 0, DEFAULT_PAGE_SIZE, url);
    }

    // ------------------------------------------------------------------
    // Rendering helpers — v5 metadata-based projections.
    // resourceMetadataRotateKey = e2eeMetadataBasedCommon + resource_type_id;
    // folders use e2eeMetadataBasedCommon; tags use tagV5MetadataRotateKey
    // (e2eeMetadataBased + id). The `personal`/`folder_parent_id` common fields
    // are emitted with the tree-independent defaults (personal=true,
    // folder_parent_id=null) used by the official rotate-key index examples,
    // keeping this domain free of cross-domain folder-tree coupling.
    // ------------------------------------------------------------------

    private Map<String, Object> toResourceRotateView(Resource resource) {
        Map<String, Object> view = e2eeMetadataBasedCommon(resource.getId(), resource.getMetadata(),
                resource.getMetadataKeyId(), resource.getMetadataKeyType(),
                resource.getCreated(), resource.getModified(),
                resource.getCreatedBy(), resource.getModifiedBy());
        view.put("resource_type_id", resource.getResourceTypeId());
        return view;
    }

    private Map<String, Object> toFolderRotateView(Folder folder) {
        return e2eeMetadataBasedCommon(folder.getId(), folder.getMetadata(),
                folder.getMetadataKeyId(), folder.getMetadataKeyType(),
                folder.getCreated(), folder.getModified(),
                folder.getCreatedBy(), folder.getModifiedBy());
    }

    private Map<String, Object> toTagRotateView(Tag tag) {
        // tagV5MetadataRotateKey = e2eeMetadataBased + id (no created/modified
        // /personal fields — EE Tags carry no created_by/modified_by columns).
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", tag.getId());
        view.put("metadata", tag.getMetadata());
        view.put("metadata_key_id", tag.getMetadataKeyId());
        view.put("metadata_key_type", tag.getMetadataKeyType());
        return view;
    }

    private Map<String, Object> e2eeMetadataBasedCommon(String id, String metadata,
            String metadataKeyId, String metadataKeyType, Object created, Object modified,
            String createdBy, String modifiedBy) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("metadata", metadata);
        view.put("metadata_key_id", metadataKeyId);
        view.put("metadata_key_type", metadataKeyType);
        view.put("created", created);
        view.put("modified", modified);
        view.put("created_by", createdBy);
        view.put("modified_by", modifiedBy);
        view.put("personal", true);
        view.put("folder_parent_id", null);
        return view;
    }

    // ------------------------------------------------------------------
    // Envelope + security helpers
    // ------------------------------------------------------------------

    /**
     * Wrap the body list in the standard success envelope and inject the
     * {@code headerWithPagination} block required by the rotate-key index
     * responses.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> paginated(List<Map<String, Object>> body,
            int page, int limit, String url) {
        Map<String, Object> response = ApiResponse.success("The operation was successful.", body, url);

        Map<String, Object> header = new LinkedHashMap<>((Map<String, Object>) response.get("header"));
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("count", body.size());
        pagination.put("page", Math.max(page, 0) + 1);
        pagination.put("limit", limit <= 0 ? DEFAULT_PAGE_SIZE : limit);
        header.put("pagination", pagination);
        response.put("header", header);

        return ResponseEntity.ok(response);
    }

    /**
     * Admin gate (PHP {@code accessRestrictedToAdministrators}). Returns a 403
     * response when the current user is not an admin, otherwise null.
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
        Optional<User> user = userRepository.findByUsername(username);
        return user.map(User::getId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "User not found: " + username));
    }
}
