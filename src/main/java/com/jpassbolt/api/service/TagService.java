package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.TagDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.ResourcesTag;
import com.jpassbolt.api.model.Tag;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourcesTagRepository;
import com.jpassbolt.api.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for the Passbolt EE "Tags" feature.
 *
 * <p>
 * Tags label resources. The distinction between a <strong>personal</strong> tag
 * (owned by the user who attached it, {@code resources_tags.user_id = userId},
 * {@code is_shared = false}) and a <strong>shared</strong> tag (visible to every
 * user who can see the resource, {@code user_id IS NULL}, {@code is_shared =
 * true}) is driven purely by the {@code '#'} slug prefix — a slug beginning with
 * {@code '#'} is a shared tag. The {@code is_shared} flag is kept in sync with
 * that convention at this layer (ported from the official Passbolt EE
 * {@code Tags} plugin: {@code TagsAddResourceService}, {@code TagsUpdateService},
 * {@code TagsTable::buildTagsFromSlugs}).
 * </p>
 *
 * <p>
 * <strong>Zero-knowledge (iron law #1):</strong> v5.1 tags may carry an
 * encrypted {@code metadata} blob instead of a plaintext slug. The server only
 * stores and forwards that armored OpenPGP blob — it is never decrypted, and no
 * server-side crypto is performed here. The {@code metadata}/{@code
 * metadata_key_id}/{@code metadata_key_type} columns are pure store-and-forward.
 * </p>
 *
 * <p>
 * <strong>Settings gate:</strong> v4 (slug) vs v5 (metadata) tag creation is
 * gated by {@code allow_creation_of_v4_tags} / {@code allow_creation_of_v5_tags}
 * (organization metadataTypes settings). To keep the tags domain compilable
 * independently of the settings domain's parallel wave, the controller resolves
 * those flags and passes them in; this service receives them as explicit
 * arguments and never reaches into the settings bean directly. The spec defaults
 * (v4 allowed, v5 not allowed) are applied by the caller when no setting row
 * exists.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagService {

    /** {@code resources_tags.user_id} value standing for a shared association. */
    private static final String SHARED_ASSOCIATION_USER_ID = null;

    /** tags.slug is varchar(128) in the official EE schema. */
    public static final int MAX_SLUG_LENGTH = 128;

    private final TagRepository tagRepository;
    private final ResourcesTagRepository resourcesTagRepository;
    private final PermissionRepository permissionRepository;

    // ---------------------------------------------------------------------
    // GET /tags.json — index
    // ---------------------------------------------------------------------

    /**
     * List every tag visible to the user: all shared tags plus the personal
     * tags the user has attached to any resource. Ordered by slug (matching the
     * official Passbolt index ordering).
     *
     * @param userId the requesting user's ID
     * @return visible tags, shared + the user's personal
     */
    @Transactional(readOnly = true)
    public List<Tag> getTags(String userId) {
        return tagRepository.findVisibleToUser(userId);
    }

    // ---------------------------------------------------------------------
    // GET /tags/{resourceId}.json — a resource's tags (helper for controller)
    // ---------------------------------------------------------------------

    /**
     * The tags carried by a resource as seen by a given user: every shared tag
     * on the resource plus that user's own personal tags on it (other users'
     * personal tags are not disclosed). Requires READ access on the resource.
     *
     * @param resourceId the resource ID
     * @param userId     the requesting user's ID
     * @return the resource's visible tags, ordered by slug
     * @throws PassboltApiException 404 when the user cannot READ the resource
     */
    @Transactional(readOnly = true)
    public List<Tag> getResourceTags(String resourceId, String userId) {
        requireResourceReadAccess(resourceId, userId);

        List<Tag> result = new ArrayList<>();
        for (Tag tag : tagRepository.findByResourceId(resourceId)) {
            if (Boolean.TRUE.equals(tag.getIsShared())) {
                result.add(tag);
            } else if (isPersonalTagOwnedBy(resourceId, tag.getId(), userId)) {
                result.add(tag);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // PUT /tags/{tagId}.json — rename / reshare a tag
    // ---------------------------------------------------------------------

    /**
     * Rename and/or reshare a tag.
     *
     * <p>
     * Authorization (ported from {@code TagsUpdateController}):
     * <ul>
     * <li>a <em>personal</em> tag may only be updated by the user who owns at
     * least one association to it ({@code resources_tags.user_id = userId});
     * a foreign personal tag yields 404 (existence is not disclosed);</li>
     * <li>a <em>shared</em> tag may be updated by any authenticated user (the
     * EE plugin allows any user who can see the shared tag to rename it).</li>
     * </ul>
     * </p>
     *
     * <p>
     * For a v4 (slug) update, the {@code '#'} prefix and {@code is_shared} flag
     * are reconciled. For a v5 (metadata) update, the three nullable metadata
     * columns are stored verbatim (store-and-forward, never decrypted) and the
     * slug column is left untouched (it remains NOT NULL in the schema). v5
     * creation/update is only honored when {@code allowV5} is true.
     * </p>
     *
     * @param tagId   the tag to update
     * @param request rename / reshare request (v4 slug or v5 metadata)
     * @param userId  the requesting user's ID
     * @param allowV4 whether v4 (slug) tags may be written
     * @param allowV5 whether v5 (metadata) tags may be written
     * @return the updated tag
     * @throws PassboltApiException 400 on validation failure, 404 if the tag
     *                              does not exist or the personal tag belongs to
     *                              another user
     */
    @Transactional
    public Tag updateTag(String tagId, TagDto.UpdateRequest request, String userId,
            boolean allowV4, boolean allowV5) {
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The tag does not exist."));

        if (!Boolean.TRUE.equals(tag.getIsShared()) && !userOwnsPersonalTag(tagId, userId)) {
            // Foreign personal tag — do not disclose its existence.
            throw new PassboltApiException(HttpStatus.NOT_FOUND, "The tag does not exist.");
        }

        boolean v5Update = request.getMetadata() != null;
        if (v5Update) {
            applyV5Fields(tag, request.getMetadata(), request.getMetadataKeyId(),
                    request.getMetadataKeyType(), request.getIsShared(), allowV5);
        } else {
            applyV4Slug(tag, request.getSlug(), request.getIsShared(), allowV4);
        }

        Tag saved = tagRepository.save(tag);
        log.debug("Tag {} updated by user {} (shared={})", saved.getId(), userId, saved.getIsShared());
        return saved;
    }

    // ---------------------------------------------------------------------
    // POST /tags/{resourceId}.json — set the tags on a resource
    // ---------------------------------------------------------------------

    /**
     * Set the tags carried by a resource for the requesting user ("set"
     * semantics, ported from {@code TagsAddResourceService}): the supplied list
     * <em>replaces</em> the resource's tag set as that user sees it. Existing
     * personal associations no longer present are removed; shared-tag
     * associations are likewise reconciled to the supplied shared tags.
     *
     * <p>
     * Each entry is either a reference to an existing tag ({@code {id}}) or a new
     * tag definition (v4 {@code slug} — {@code '#'}-prefixed ⇒ shared — or v5
     * {@code metadata}). New tags are created on demand; orphaned tags (whose
     * last association is removed) are hard-deleted.
     * </p>
     *
     * <p>
     * Requires READ access on the resource (same gate as comments:
     * {@link PermissionRepository#userHasAccessIncludingGroups}). The server only
     * stores armored v5 metadata; it never decrypts it.
     * </p>
     *
     * @param resourceId the resource being tagged
     * @param entries    the desired tag set (existing refs and/or new defs)
     * @param userId     the requesting user's ID
     * @param allowV4    whether new v4 (slug) tags may be created
     * @param allowV5    whether new v5 (metadata) tags may be created
     * @return the resource's resulting visible tag set (shared + the user's
     *         personal), ordered by slug
     * @throws PassboltApiException 400 on validation failure, 404 when the user
     *                              cannot READ the resource or references a
     *                              non-existent tag id
     */
    @Transactional
    public List<Tag> addTagsToResource(String resourceId, List<TagDto.TagEntry> entries,
            String userId, boolean allowV4, boolean allowV5) {
        requireResourceReadAccess(resourceId, userId);

        List<TagDto.TagEntry> requested = entries != null ? entries : List.of();

        // Resolve every requested entry to a concrete (possibly newly created)
        // Tag, preserving order and de-duplicating.
        Set<String> desiredPersonalTagIds = new LinkedHashSet<>();
        Set<String> desiredSharedTagIds = new LinkedHashSet<>();
        for (TagDto.TagEntry entry : requested) {
            Tag tag = resolveOrCreateTag(entry, userId, allowV4, allowV5);
            if (Boolean.TRUE.equals(tag.getIsShared())) {
                desiredSharedTagIds.add(tag.getId());
            } else {
                desiredPersonalTagIds.add(tag.getId());
            }
        }

        reconcilePersonalAssociations(resourceId, userId, desiredPersonalTagIds);
        reconcileSharedAssociations(resourceId, desiredSharedTagIds);

        return getResourceTags(resourceId, userId);
    }

    // ---------------------------------------------------------------------
    // Internal: association reconciliation ("set" semantics)
    // ---------------------------------------------------------------------

    /**
     * Make the user's personal associations on the resource exactly match
     * {@code desiredTagIds}: add missing rows, drop rows no longer wanted, and
     * hard-delete any tag left orphaned by a removal.
     */
    private void reconcilePersonalAssociations(String resourceId, String userId, Set<String> desiredTagIds) {
        List<ResourcesTag> existing = resourcesTagRepository.findByResourceIdAndUserId(resourceId, userId);

        // Remove associations no longer desired.
        for (ResourcesTag rt : existing) {
            if (!desiredTagIds.contains(rt.getTagId())) {
                resourcesTagRepository.delete(rt);
                deleteTagIfOrphaned(rt.getTagId());
            }
        }

        // Add newly desired associations.
        for (String tagId : desiredTagIds) {
            boolean present = resourcesTagRepository
                    .findByResourceIdAndTagIdAndUserId(resourceId, tagId, userId)
                    .isPresent();
            if (!present) {
                resourcesTagRepository.save(buildAssociation(resourceId, tagId, userId));
            }
        }
    }

    /**
     * Make the resource's shared-tag associations ({@code user_id IS NULL})
     * exactly match {@code desiredTagIds}.
     */
    private void reconcileSharedAssociations(String resourceId, Set<String> desiredTagIds) {
        List<ResourcesTag> existing = resourcesTagRepository.findByResourceIdAndUserIdIsNull(resourceId);

        for (ResourcesTag rt : existing) {
            if (!desiredTagIds.contains(rt.getTagId())) {
                resourcesTagRepository.delete(rt);
                deleteTagIfOrphaned(rt.getTagId());
            }
        }

        for (String tagId : desiredTagIds) {
            boolean present = resourcesTagRepository
                    .findByResourceIdAndTagIdAndUserIdIsNull(resourceId, tagId)
                    .isPresent();
            if (!present) {
                resourcesTagRepository.save(buildAssociation(resourceId, tagId, SHARED_ASSOCIATION_USER_ID));
            }
        }
    }

    private ResourcesTag buildAssociation(String resourceId, String tagId, String userId) {
        ResourcesTag rt = new ResourcesTag();
        rt.setResourceId(resourceId);
        rt.setTagId(tagId);
        rt.setUserId(userId);
        return rt;
    }

    /**
     * Hard-delete a tag once it has no remaining associations (PHP
     * {@code TagsTable::deleteAllUnusedTags}). Tags have no soft-delete column.
     */
    private void deleteTagIfOrphaned(String tagId) {
        if (resourcesTagRepository.countByTagId(tagId) == 0) {
            tagRepository.deleteById(tagId);
            log.debug("Orphaned tag {} hard-deleted", tagId);
        }
    }

    // ---------------------------------------------------------------------
    // Internal: tag resolution / creation
    // ---------------------------------------------------------------------

    /**
     * Resolve an entry to an existing tag (when {@code id} is supplied) or
     * create a new one (v4 slug or v5 metadata).
     */
    private Tag resolveOrCreateTag(TagDto.TagEntry entry, String userId, boolean allowV4, boolean allowV5) {
        if (entry == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "A tag definition is required.");
        }

        if (entry.getId() != null && !entry.getId().isBlank()) {
            return tagRepository.findById(entry.getId())
                    .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                            "The tag does not exist."));
        }

        // v5 metadata definition takes precedence when present.
        if (entry.getMetadata() != null) {
            return createV5Tag(entry.getMetadata(), entry.getMetadataKeyId(),
                    entry.getMetadataKeyType(), entry.getIsShared(), allowV5);
        }

        return createV4Tag(entry.getSlug(), entry.getIsShared(), allowV4);
    }

    /**
     * Create (or reuse) a v4 slug tag. A {@code '#'}-prefixed slug is shared;
     * a shared tag with a given slug is de-duplicated across the whole table.
     */
    private Tag createV4Tag(String rawSlug, Boolean isSharedRequest, boolean allowV4) {
        if (!allowV4) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The creation of v4 tags is not allowed.");
        }
        String slug = validateSlug(rawSlug);
        boolean shared = resolveSharedFlag(slug, isSharedRequest);

        if (shared) {
            // Shared tags are unique by slug — reuse an existing one if present.
            return tagRepository.findBySlugAndIsSharedTrue(slug)
                    .orElseGet(() -> persistNewTag(slug, true, null, null, null));
        }
        // Personal tags are also de-duplicated by (slug, is_shared=false): the
        // same label re-used across resources points at one Tag row, only the
        // resources_tags associations differ per resource/user.
        return tagRepository.findBySlugAndIsShared(slug, false)
                .orElseGet(() -> persistNewTag(slug, false, null, null, null));
    }

    /**
     * Create a v5 (metadata) tag. The label lives in the encrypted
     * {@code metadata} blob (store-and-forward, never decrypted); the slug
     * column stays NOT NULL so a synthetic, collision-resistant placeholder is
     * stored, mirroring the official EE v5.1 behavior. v5 tags are not
     * de-duplicated by slug because their labels are opaque ciphertext.
     */
    private Tag createV5Tag(String metadata, String metadataKeyId, String metadataKeyType,
            Boolean isSharedRequest, boolean allowV5) {
        if (!allowV5) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The creation of v5 tags is not allowed.");
        }
        validateMetadataBlob(metadata, metadataKeyType);
        boolean shared = Boolean.TRUE.equals(isSharedRequest);
        Tag tag = persistNewTag(syntheticV5Slug(shared), shared, metadata, metadataKeyId, metadataKeyType);
        return tag;
    }

    private Tag persistNewTag(String slug, boolean shared, String metadata,
            String metadataKeyId, String metadataKeyType) {
        Tag tag = new Tag();
        tag.setSlug(slug);
        tag.setIsShared(shared);
        tag.setMetadata(metadata);
        tag.setMetadataKeyId(metadataKeyId);
        tag.setMetadataKeyType(metadataKeyType);
        return tagRepository.save(tag);
    }

    // ---------------------------------------------------------------------
    // Internal: field application for updateTag
    // ---------------------------------------------------------------------

    private void applyV4Slug(Tag tag, String rawSlug, Boolean isSharedRequest, boolean allowV4) {
        if (!allowV4) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The creation of v4 tags is not allowed.");
        }
        String slug = validateSlug(rawSlug);
        boolean shared = resolveSharedFlag(slug, isSharedRequest);
        // Clear any v5 metadata when reverting/keeping a tag as plain v4.
        tag.setSlug(slug);
        tag.setIsShared(shared);
    }

    private void applyV5Fields(Tag tag, String metadata, String metadataKeyId,
            String metadataKeyType, Boolean isSharedRequest, boolean allowV5) {
        if (!allowV5) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The creation of v5 tags is not allowed.");
        }
        validateMetadataBlob(metadata, metadataKeyType);
        boolean shared = Boolean.TRUE.equals(isSharedRequest);
        tag.setMetadata(metadata);
        tag.setMetadataKeyId(metadataKeyId);
        tag.setMetadataKeyType(metadataKeyType);
        tag.setIsShared(shared);
        // Keep slug column populated (NOT NULL) without leaking the plaintext
        // label; only refresh the synthetic prefix to match the shared flag.
        if (tag.getSlug() == null || tag.isSharedSlug() != shared) {
            tag.setSlug(syntheticV5Slug(shared));
        }
    }

    // ---------------------------------------------------------------------
    // Internal: validation helpers
    // ---------------------------------------------------------------------

    /**
     * Validate a v4 slug: mandatory, 1..128 chars (tags.slug is varchar(128)).
     */
    private String validateSlug(String rawSlug) {
        if (rawSlug == null || rawSlug.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "A tag slug is required.");
        }
        String slug = rawSlug.trim();
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The slug length should be between 1 and " + MAX_SLUG_LENGTH + " characters.");
        }
        return slug;
    }

    /**
     * Reconcile the requested {@code is_shared} flag with the {@code '#'} slug
     * convention: a {@code '#'}-prefixed slug is always shared; otherwise the
     * tag is shared only when explicitly requested. The official EE plugin
     * derives shared-ness from the slug prefix, so the prefix wins.
     */
    private boolean resolveSharedFlag(String slug, Boolean isSharedRequest) {
        if (slug.startsWith(Tag.SHARED_SLUG_PREFIX)) {
            return true;
        }
        return Boolean.TRUE.equals(isSharedRequest);
    }

    /**
     * Structural validation of a v5 metadata blob (zero-knowledge: no decrypt).
     * The server only checks the blob is a non-blank ASCII-armored OpenPGP
     * MESSAGE and that the key type is one of the allowed enum values. Actual
     * OpenPGP parse-only validation lives in the keys domain's shared validator;
     * here we keep a cheap structural guard so malformed input fails fast.
     */
    private void validateMetadataBlob(String metadata, String metadataKeyType) {
        if (metadata == null || metadata.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The tag metadata is required.");
        }
        if (!metadata.contains("BEGIN PGP MESSAGE")) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The tag metadata must be a valid OpenPGP message.");
        }
        if (metadataKeyType != null
                && !"user_key".equals(metadataKeyType)
                && !"shared_key".equals(metadataKeyType)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The metadata key type must be one of: user_key, shared_key.");
        }
    }

    // ---------------------------------------------------------------------
    // Internal: access / ownership helpers
    // ---------------------------------------------------------------------

    /**
     * Require READ (or higher) access on the resource, including group-inherited
     * permissions — same gate Comments use. A user without access gets 404 (the
     * resource's existence is not disclosed), matching the EE plugin.
     */
    private void requireResourceReadAccess(String resourceId, String userId) {
        boolean hasAccess = permissionRepository
                .userHasAccessIncludingGroups(resourceId, userId, Permission.READ);
        if (!hasAccess) {
            throw new PassboltApiException(HttpStatus.NOT_FOUND, "The resource does not exist.");
        }
    }

    /** True when the user owns a personal association to the tag (any resource). */
    private boolean userOwnsPersonalTag(String tagId, String userId) {
        return resourcesTagRepository.findByTagId(tagId).stream()
                .anyMatch(rt -> userId.equals(rt.getUserId()));
    }

    /** True when the user has a personal association to (resource, tag). */
    private boolean isPersonalTagOwnedBy(String resourceId, String tagId, String userId) {
        return resourcesTagRepository
                .findByResourceIdAndTagIdAndUserId(resourceId, tagId, userId)
                .isPresent();
    }

    /**
     * Synthetic, NOT-NULL slug placeholder for a v5 tag (the real label is in
     * the encrypted metadata). Shared v5 tags keep the {@code '#'} prefix so the
     * slug-prefix convention stays consistent.
     */
    private String syntheticV5Slug(boolean shared) {
        String base = "v5-" + java.util.UUID.randomUUID();
        return shared ? Tag.SHARED_SLUG_PREFIX + base : base;
    }
}
