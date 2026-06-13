package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.MetadataUpgradeDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Tag;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.MetadataUpgradeService;
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
 * REST endpoints for the v5 Metadata <b>upgrade</b> (v4&#8594;v5) domain:
 * {@code /metadata/upgrade/{resources,folders,tags}.json}.
 *
 * <p>
 * Each model exposes the same two handlers:
 * <ul>
 *   <li>{@code GET} — paginated index of the entities still eligible for upgrade
 *       (a v4 entity whose {@code metadata} column is still NULL);</li>
 *   <li>{@code POST} — apply the upgrade for an array of posted elements, writing
 *       ONLY the three additive nullable v5 columns
 *       ({@code metadata}/{@code metadata_key_id}/{@code metadata_key_type}). The
 *       v4 {@code name}/{@code username}/{@code uri}/{@code description} columns
 *       are never touched, so v4 contract behaviour is preserved.</li>
 * </ul>
 * Both handlers respond with the (re-computed) list of <em>still upgradeable</em>
 * entities rendered in the v4 "index and view" projection
 * ({@code resourceV4IndexAndView} / {@code folderV4IndexAndView} /
 * {@code tagLegacy}) wrapped in the {@code headerWithPagination} envelope.
 * </p>
 *
 * <h3>Security</h3>
 * <p>
 * Every endpoint is JWT-protected by {@code SecurityConfig}
 * ({@code anyRequest().authenticated()}). The upgrade endpoints are additionally
 * admin-only: a non-admin principal is rejected with a 403
 * {@code accessRestrictedToAdministrators} (mirrors the
 * {@code MetadataKeyController}/{@code UsersController} pattern). The
 * {@code allow_v4_v5_upgrade} settings gate (default off) is enforced in
 * {@link MetadataUpgradeService} and surfaces as a 403 as well.
 * </p>
 *
 * <h3>Iron Laws</h3>
 * <p>
 * Zero-knowledge (#1): the {@code metadata} payload is an armored OpenPGP MESSAGE
 * that this controller and {@link MetadataUpgradeService} only store/forward —
 * never decrypt. DTOs are transport-only (#3); all business rules, validation and
 * the optimistic-lock conflict detection live in the service.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/metadata")
@RequiredArgsConstructor
public class MetadataUpgradeController {

    /** Default page size for the upgrade index (PHP metadata pagination limit). */
    private static final int DEFAULT_PAGE_SIZE = MetadataUpgradeService.DEFAULT_PAGE_SIZE;

    private final MetadataUpgradeService metadataUpgradeService;
    private final UserService userService;
    private final UserRepository userRepository;

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    /**
     * {@code GET /metadata/upgrade/resources.json} — paginated index of v4
     * resources still eligible for upgrade. Admin only.
     */
    @GetMapping("/upgrade/resources.json")
    public ResponseEntity<Map<String, Object>> indexUpgradableResources(
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        String url = "/metadata/upgrade/resources.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        List<Resource> resources = metadataUpgradeService.findUpgradableResources(page, limit);
        List<Map<String, Object>> body = resources.stream()
                .map(this::toResourceV4View)
                .collect(Collectors.toList());
        return paginated(body, page, limit, url);
    }

    /**
     * {@code POST /metadata/upgrade/resources.json} — upgrade an array of v4
     * resources to v5 (additive). Admin only. Responds with the recomputed list
     * of still-upgradeable resources.
     */
    @PostMapping("/upgrade/resources.json")
    public ResponseEntity<Map<String, Object>> upgradeResources(
            @RequestBody List<MetadataUpgradeDto.UpgradeRequest> request) {
        String url = "/metadata/upgrade/resources.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        metadataUpgradeService.upgradeResources(request, getCurrentUserId());

        List<Map<String, Object>> body = metadataUpgradeService.findUpgradableResources(0, DEFAULT_PAGE_SIZE)
                .stream().map(this::toResourceV4View).collect(Collectors.toList());
        return paginated(body, 0, DEFAULT_PAGE_SIZE, url);
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    /**
     * {@code GET /metadata/upgrade/folders.json} — paginated index of v4 folders
     * still eligible for upgrade. Admin only.
     */
    @GetMapping("/upgrade/folders.json")
    public ResponseEntity<Map<String, Object>> indexUpgradableFolders(
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        String url = "/metadata/upgrade/folders.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        List<Folder> folders = metadataUpgradeService.findUpgradableFolders(page, limit);
        List<Map<String, Object>> body = folders.stream()
                .map(this::toFolderV4View)
                .collect(Collectors.toList());
        return paginated(body, page, limit, url);
    }

    /**
     * {@code POST /metadata/upgrade/folders.json} — upgrade an array of v4
     * folders to v5 (additive). Admin only.
     */
    @PostMapping("/upgrade/folders.json")
    public ResponseEntity<Map<String, Object>> upgradeFolders(
            @RequestBody List<MetadataUpgradeDto.UpgradeRequest> request) {
        String url = "/metadata/upgrade/folders.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        metadataUpgradeService.upgradeFolders(request, getCurrentUserId());

        List<Map<String, Object>> body = metadataUpgradeService.findUpgradableFolders(0, DEFAULT_PAGE_SIZE)
                .stream().map(this::toFolderV4View).collect(Collectors.toList());
        return paginated(body, 0, DEFAULT_PAGE_SIZE, url);
    }

    // ------------------------------------------------------------------
    // Tags (v5.1)
    // ------------------------------------------------------------------

    /**
     * {@code GET /metadata/upgrade/tags.json} — paginated index of v4 tags still
     * eligible for upgrade (v5.1). Admin only.
     */
    @GetMapping("/upgrade/tags.json")
    public ResponseEntity<Map<String, Object>> indexUpgradableTags(
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        String url = "/metadata/upgrade/tags.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        List<Tag> tags = metadataUpgradeService.findUpgradableTags(page, limit);
        List<Map<String, Object>> body = tags.stream()
                .map(this::toTagLegacyView)
                .collect(Collectors.toList());
        return paginated(body, page, limit, url);
    }

    /**
     * {@code POST /metadata/upgrade/tags.json} — upgrade an array of v4 tags to
     * v5 (additive, v5.1). Admin only.
     */
    @PostMapping("/upgrade/tags.json")
    public ResponseEntity<Map<String, Object>> upgradeTags(
            @RequestBody List<MetadataUpgradeDto.UpgradeRequest> request) {
        String url = "/metadata/upgrade/tags.json";
        ResponseEntity<Map<String, Object>> guard = requireAdmin(url);
        if (guard != null) {
            return guard;
        }

        metadataUpgradeService.upgradeTags(request, getCurrentUserId());

        List<Map<String, Object>> body = metadataUpgradeService.findUpgradableTags(0, DEFAULT_PAGE_SIZE)
                .stream().map(this::toTagLegacyView).collect(Collectors.toList());
        return paginated(body, 0, DEFAULT_PAGE_SIZE, url);
    }

    // ------------------------------------------------------------------
    // Rendering helpers — v4 "index and view" projections.
    // The upgrade index/apply intentionally projects the v4 shape (the entities
    // are still v4 until upgraded), so only the v4 columns are surfaced. The
    // `personal`/`folder_parent_id` fields are required by the spec; they are
    // emitted with the per-user-tree-independent defaults (personal=true,
    // folder_parent_id=null) used by the official upgrade index examples, which
    // keeps this domain free of any cross-domain folder-tree coupling.
    // ------------------------------------------------------------------

    private Map<String, Object> toResourceV4View(Resource resource) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", resource.getId());
        view.put("name", resource.getName());
        view.put("username", resource.getUsername());
        view.put("uri", resource.getUri());
        view.put("description", resource.getDescription());
        view.put("deleted", Boolean.TRUE.equals(resource.getDeleted()));
        view.put("created", resource.getCreated());
        view.put("modified", resource.getModified());
        view.put("created_by", resource.getCreatedBy());
        view.put("modified_by", resource.getModifiedBy());
        view.put("resource_type_id", resource.getResourceTypeId());
        view.put("expired", resource.getExpired());
        view.put("folder_parent_id", null);
        view.put("personal", true);
        return view;
    }

    private Map<String, Object> toFolderV4View(Folder folder) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", folder.getId());
        view.put("name", folder.getName());
        view.put("created", folder.getCreated());
        view.put("modified", folder.getModified());
        view.put("created_by", folder.getCreatedBy());
        view.put("modified_by", folder.getModifiedBy());
        view.put("folder_parent_id", null);
        view.put("personal", true);
        return view;
    }

    private Map<String, Object> toTagLegacyView(Tag tag) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", tag.getId());
        view.put("user_id", null);
        view.put("slug", tag.getSlug());
        view.put("is_shared", Boolean.TRUE.equals(tag.getIsShared()));
        return view;
    }

    // ------------------------------------------------------------------
    // Envelope + security helpers
    // ------------------------------------------------------------------

    /**
     * Wrap the body list in the standard success envelope and inject the
     * {@code headerWithPagination} block ({@code count}/{@code page}/
     * {@code limit}) required by the upgrade index responses.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> paginated(List<Map<String, Object>> body,
            int page, int limit, String url) {
        Map<String, Object> response = ApiResponse.success("The operation was successful.", body, url);

        Map<String, Object> header = new LinkedHashMap<>((Map<String, Object>) response.get("header"));
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("count", body.size());
        // PHP pagination page numbers are 1-based; the (0-based) request page maps
        // to page+1 in the response header.
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
