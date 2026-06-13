package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.TagDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Tag;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.MetadataTypesSettingsService;
import com.jpassbolt.api.service.TagService;
import com.jpassbolt.api.util.ApiResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * REST endpoints for the Passbolt EE "Tags" feature (and v5.1 encrypted-metadata
 * tags). All three endpoints are JWT-protected (no admin gate) and fall through
 * to {@code .anyRequest().authenticated()} in {@code SecurityConfig}.
 *
 * <p>
 * Endpoints (matching the OpenAPI spec under {@code /tags}):
 * <ul>
 * <li>{@code GET /tags.json} — the current user's personal tags plus all shared
 * tags (the {@code tagIndexAndView} anyOf union of {@code tagLegacy} /
 * {@code tagV5}).</li>
 * <li>{@code PUT /tags/{resourceOrTagId}.json} — rename / reshare a single tag.
 * The path id is a <em>tag</em> id here.</li>
 * <li>{@code POST /tags/{resourceOrTagId}.json} — set the tags carried by a
 * resource ("set" semantics). The path id is a <em>resource</em> id here, hence
 * the shared {@code resourceOrTagId} path parameter name.</li>
 * </ul>
 * </p>
 *
 * <p>
 * Personal vs shared is driven by the {@code '#'} slug convention (a
 * {@code '#'}-prefixed slug is a shared tag); v4 (slug) vs v5 (encrypted
 * metadata) creation is gated by the organization {@code allow_creation_of_v4_tags}
 * / {@code allow_creation_of_v5_tags} settings, resolved here via
 * {@link MetadataTypesSettingsService} and passed into {@link TagService} (which
 * holds no settings dependency of its own). The server only stores/forwards the
 * armored v5 {@code metadata} blob — it never decrypts it (iron law #1).
 * </p>
 *
 * <p>
 * Note on mappings: no class-level {@code @RequestMapping} (Boot 3
 * PathPatternParser would turn {@code "/tags"} + {@code ".json"} into
 * {@code "/tags/.json"}), full method-level paths instead — same rationale as
 * {@code FolderController}/{@code ResourceController}.
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TagController {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final TagService tagService;
    private final MetadataTypesSettingsService metadataTypesSettingsService;
    private final UserRepository userRepository;

    /**
     * GET /tags.json
     * Index: every tag visible to the current user — all shared tags plus the
     * personal tags the user has attached to any resource. Returns the
     * {@code tagIndexAndView} anyOf union: v4 tags as {@code tagLegacy}, v5.1
     * tags (those carrying encrypted {@code metadata}) as {@code tagV5}.
     */
    @GetMapping({ "/tags", "/tags.json" })
    public ResponseEntity<Map<String, Object>> indexTags() {
        String url = "/tags.json";
        String userId = getCurrentUserId();

        List<Tag> tags = tagService.getTags(userId);
        List<Object> body = tags.stream()
                .map(tag -> toIndexView(tag, userId))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", body, url));
    }

    /**
     * PUT /tags/{resourceOrTagId}.json
     * Rename / reshare a single tag. The path id is a tag id. A personal tag may
     * only be updated by the user who owns it (a foreign personal tag yields 404,
     * not disclosing its existence); a shared tag may be updated by any
     * authenticated user. The response is {@code tagV5} when the tag carries
     * encrypted metadata, otherwise {@code tagLegacy}.
     */
    @PutMapping("/tags/{resourceOrTagId}.json")
    public ResponseEntity<Map<String, Object>> updateTag(
            @PathVariable String resourceOrTagId,
            @RequestBody TagDto.UpdateRequest request) {
        String url = "/tags/" + resourceOrTagId + ".json";
        String userId = getCurrentUserId();

        requireUuid(resourceOrTagId, "The tag id is not valid.");

        Tag updated = tagService.updateTag(resourceOrTagId, request, userId,
                metadataTypesSettingsService.isV4TagCreationAllowed(),
                metadataTypesSettingsService.isV5TagCreationAllowed());

        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", toView(updated, userId), url));
    }

    /**
     * POST /tags/{resourceOrTagId}.json
     * Set the tags carried by a resource for the current user ("set" semantics).
     * The path id is a resource id. The supplied list replaces the resource's
     * tag set as that user sees it; new tags (v4 slug or v5 metadata) are created
     * on demand and orphaned tags are hard-deleted. Requires READ access on the
     * resource (a user without access gets 404). Returns the resource's resulting
     * visible tag set ({@code tagIndexAndView[]}).
     */
    @PostMapping("/tags/{resourceOrTagId}.json")
    public ResponseEntity<Map<String, Object>> addTagsToResource(
            @PathVariable String resourceOrTagId,
            @RequestBody TagDto.AddRequest request) {
        String url = "/tags/" + resourceOrTagId + ".json";
        String userId = getCurrentUserId();

        requireUuid(resourceOrTagId, "The resource identifier should be a valid UUID.");

        List<TagDto.TagEntry> entries = request != null ? request.getTags() : null;
        List<Tag> resultTags = tagService.addTagsToResource(resourceOrTagId, entries, userId,
                metadataTypesSettingsService.isV4TagCreationAllowed(),
                metadataTypesSettingsService.isV5TagCreationAllowed());

        List<Object> body = resultTags.stream()
                .map(tag -> toIndexView(tag, userId))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", body, url));
    }

    // ------------------------------------------------------------------
    // View mapping (Tag -> tagLegacy / tagV5 union element)
    // ------------------------------------------------------------------

    /**
     * Map a tag to its index/view element. A tag carrying encrypted
     * {@code metadata} is rendered as {@code tagV5}; otherwise {@code tagLegacy}.
     * For the index/add views the {@code user_id} of a personal tag is the
     * requesting user (the only personal tags returned are the user's own);
     * shared tags carry a null {@code user_id}.
     */
    private Object toIndexView(Tag tag, String userId) {
        if (tag.getMetadata() != null) {
            return toV5(tag);
        }
        String tagUserId = Boolean.TRUE.equals(tag.getIsShared()) ? null : userId;
        return TagDto.LegacyResponse.builder()
                .id(tag.getId())
                .slug(tag.getSlug())
                .isShared(tag.getIsShared())
                .userId(tagUserId)
                .build();
    }

    /**
     * Map a tag to its single-tag view (PUT response). The {@code user_id} is
     * omitted for an updated tag (the spec's {@code tagLegacy}/{@code tagV5}
     * response examples for an update do not echo it back); {@code is_shared}
     * carries the personal/shared state.
     */
    private Object toView(Tag tag, String userId) {
        if (tag.getMetadata() != null) {
            return toV5(tag);
        }
        return TagDto.LegacyResponse.builder()
                .id(tag.getId())
                .slug(tag.getSlug())
                .isShared(tag.getIsShared())
                .build();
    }

    private TagDto.V5Response toV5(Tag tag) {
        return TagDto.V5Response.builder()
                .id(tag.getId())
                .metadata(tag.getMetadata())
                .metadataKeyId(tag.getMetadataKeyId())
                .metadataKeyType(tag.getMetadataKeyType())
                .isShared(tag.getIsShared())
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireUuid(String value, String message) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, message);
        }
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
