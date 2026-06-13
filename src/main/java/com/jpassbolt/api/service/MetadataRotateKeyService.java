package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.MetadataRotateKeyDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Tag;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * v5 Metadata <b>rotate-key</b> service — re-encrypts the metadata of resources
 * / folders / tags under a fresh shared metadata key after the previous key has
 * been expired (or deleted).
 *
 * <p>
 * Port of the PHP {@code Passbolt\Metadata\Service\RotateKey\*UpdateService}
 * (resources/folders) plus the v5.1 tags equivalent. Two phases per model:
 * <ul>
 *   <li><b>Index</b> — list the entities whose <em>current</em> metadata key is
 *       no longer active and therefore need their metadata re-keyed. Eligibility
 *       (PHP {@code findMetadataRotateKeyIndex}): {@code metadata_key_type =
 *       shared_key} AND {@code metadata IS NOT NULL} AND {@code metadata_key_id
 *       IS NOT NULL}, and the referenced {@code metadata_keys} row is expired (we
 *       also treat soft-deleted keys as needing rotation). Resources additionally
 *       require {@code deleted = false}.</li>
 *   <li><b>Apply</b> — for each posted element re-set {@code metadata} and the new
 *       {@code metadata_key_id}. The new key MUST currently be active and the
 *       {@code metadata_key_type} MUST be {@code shared_key}; a mismatch yields
 *       the {@code tooManyUpdatedEntities} 409 (PHP
 *       {@code IsSharedMetadataKeyUniqueActiveRule}). This is store-and-forward of
 *       the client-supplied re-encrypted blob — no server crypto.</li>
 * </ul>
 * </p>
 *
 * <h3>Iron Laws</h3>
 * <ul>
 *   <li>#1 zero-knowledge: the {@code metadata} payload is an armored OpenPGP
 *       MESSAGE; this service only stores/forwards it and NEVER decrypts.</li>
 *   <li>Repos {@link ResourceRepository}/{@link FolderRepository}/
 *       {@link TagRepository}/{@link MetadataKeyRepository} are consumed
 *       read-only — eligibility filtering and active-key resolution are done here
 *       (no finders added to those repositories).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataRotateKeyService {

    /** Default page size for the rotate-key index (PHP metadata pagination limit). */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Max batch size for a single apply (PHP MetadataPaginationComponent). */
    public static final int MAX_BATCH_SIZE = 200;

    /** PHP {@code MetadataKey::TYPE_SHARED_KEY}: rotation targets shared keys. */
    public static final String TYPE_SHARED_KEY = "shared_key";

    private final ResourceRepository resourceRepository;
    private final FolderRepository folderRepository;
    private final TagRepository tagRepository;
    private final MetadataKeyRepository metadataKeyRepository;

    // ---------------------------------------------------------------------
    // Resources
    // ---------------------------------------------------------------------

    /**
     * List resources whose current (shared) metadata key has expired/been
     * deleted and therefore need re-keying. {@code deleted = false}, ordered by
     * id, paginated.
     *
     * @param page zero-based page index
     * @param size page size (defaults to {@link #DEFAULT_PAGE_SIZE} when &lt;= 0)
     * @return the resources needing key rotation for the requested page
     */
    @Transactional(readOnly = true)
    public List<Resource> findResourcesWithExpiredKeys(int page, int size) {
        Set<String> inactiveKeyIds = inactiveKeyIds();
        List<Resource> eligible = resourceRepository.findByDeletedFalse().stream()
                .filter(r -> needsRotation(r.getMetadata(), r.getMetadataKeyId(),
                        r.getMetadataKeyType(), inactiveKeyIds))
                .sorted(Comparator.comparing(Resource::getId))
                .toList();
        return paginate(eligible, page, size);
    }

    /**
     * Re-key the given resources: re-write {@code metadata} + the new
     * {@code metadata_key_id} (which must reference an active shared key).
     *
     * @param entries the posted rotate elements
     * @param userId  the acting (admin) user id
     * @return the resources that were re-keyed by this call
     * @throws PassboltApiException 400 on an empty/oversized/invalid batch, 404
     *                              when an id does not exist, 409 when the new key
     *                              is not active / not a shared key, or on an
     *                              optimistic-lock mismatch
     */
    @Transactional
    public List<Resource> rotateResources(List<MetadataRotateKeyDto.RotateRequest> entries, String userId) {
        assertBatch(entries);

        List<Resource> updated = new ArrayList<>();
        for (MetadataRotateKeyDto.RotateRequest entry : entries) {
            validateElement(entry);
            Resource resource = resourceRepository.findById(entry.getId())
                    .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                    .orElseThrow(() -> notFound(entry.getId()));
            assertNewKeyActiveSharedKey(entry.getMetadataKeyId(), entry.getMetadataKeyType());
            assertConflict(entry, resource.getModified(), resource.getModifiedBy());

            resource.setMetadata(entry.getMetadata());
            resource.setMetadataKeyId(entry.getMetadataKeyId());
            resource.setMetadataKeyType(entry.getMetadataKeyType());
            resource.setModifiedBy(userId);
            updated.add(resourceRepository.save(resource));
        }
        log.debug("Rotated metadata key on {} resource(s) by user {}", updated.size(), userId);
        return updated;
    }

    // ---------------------------------------------------------------------
    // Folders
    // ---------------------------------------------------------------------

    /**
     * List folders whose current (shared) metadata key has expired/been deleted,
     * ordered by id, paginated.
     *
     * @param page zero-based page index
     * @param size page size (defaults to {@link #DEFAULT_PAGE_SIZE} when &lt;= 0)
     * @return the folders needing key rotation for the requested page
     */
    @Transactional(readOnly = true)
    public List<Folder> findFoldersWithExpiredKeys(int page, int size) {
        Set<String> inactiveKeyIds = inactiveKeyIds();
        List<Folder> eligible = folderRepository.findAll().stream()
                .filter(f -> needsRotation(f.getMetadata(), f.getMetadataKeyId(),
                        f.getMetadataKeyType(), inactiveKeyIds))
                .sorted(Comparator.comparing(Folder::getId))
                .toList();
        return paginate(eligible, page, size);
    }

    /**
     * Re-key the given folders.
     *
     * @param entries the posted rotate elements
     * @param userId  the acting (admin) user id
     * @return the folders that were re-keyed by this call
     * @throws PassboltApiException 400/404/409 as for
     *                              {@link #rotateResources(List, String)}
     */
    @Transactional
    public List<Folder> rotateFolders(List<MetadataRotateKeyDto.RotateRequest> entries, String userId) {
        assertBatch(entries);

        List<Folder> updated = new ArrayList<>();
        for (MetadataRotateKeyDto.RotateRequest entry : entries) {
            validateElement(entry);
            Folder folder = folderRepository.findById(entry.getId())
                    .orElseThrow(() -> notFound(entry.getId()));
            assertNewKeyActiveSharedKey(entry.getMetadataKeyId(), entry.getMetadataKeyType());
            assertConflict(entry, folder.getModified(), folder.getModifiedBy());

            folder.setMetadata(entry.getMetadata());
            folder.setMetadataKeyId(entry.getMetadataKeyId());
            folder.setMetadataKeyType(entry.getMetadataKeyType());
            folder.setModifiedBy(userId);
            updated.add(folderRepository.save(folder));
        }
        log.debug("Rotated metadata key on {} folder(s) by user {}", updated.size(), userId);
        return updated;
    }

    // ---------------------------------------------------------------------
    // Tags (v5.1)
    // ---------------------------------------------------------------------

    /**
     * List tags whose current (shared) metadata key has expired/been deleted,
     * ordered by id, paginated (v5.1).
     *
     * @param page zero-based page index
     * @param size page size (defaults to {@link #DEFAULT_PAGE_SIZE} when &lt;= 0)
     * @return the tags needing key rotation for the requested page
     */
    @Transactional(readOnly = true)
    public List<Tag> findTagsWithExpiredKeys(int page, int size) {
        Set<String> inactiveKeyIds = inactiveKeyIds();
        List<Tag> eligible = tagRepository.findAll().stream()
                .filter(t -> needsRotation(t.getMetadata(), t.getMetadataKeyId(),
                        t.getMetadataKeyType(), inactiveKeyIds))
                .sorted(Comparator.comparing(Tag::getId))
                .toList();
        return paginate(eligible, page, size);
    }

    /**
     * Re-key the given tags (v5.1).
     *
     * @param entries the posted rotate elements
     * @param userId  the acting (admin) user id
     * @return the tags that were re-keyed by this call
     * @throws PassboltApiException 400/404/409 as for
     *                              {@link #rotateResources(List, String)}
     */
    @Transactional
    public List<Tag> rotateTags(List<MetadataRotateKeyDto.RotateRequest> entries, String userId) {
        assertBatch(entries);

        List<Tag> updated = new ArrayList<>();
        for (MetadataRotateKeyDto.RotateRequest entry : entries) {
            validateElement(entry);
            Tag tag = tagRepository.findById(entry.getId())
                    .orElseThrow(() -> notFound(entry.getId()));
            assertNewKeyActiveSharedKey(entry.getMetadataKeyId(), entry.getMetadataKeyType());
            // Tags carry no modified_by column (EE Tags schema), so the conflict
            // guard checks the modified timestamp only.
            assertConflict(entry, tag.getModified(), null);

            tag.setMetadata(entry.getMetadata());
            tag.setMetadataKeyId(entry.getMetadataKeyId());
            tag.setMetadataKeyType(entry.getMetadataKeyType());
            updated.add(tagRepository.save(tag));
        }
        log.debug("Rotated metadata key on {} tag(s) by user {}", updated.size(), userId);
        return updated;
    }

    // ---------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------

    /**
     * Ids of metadata keys that are no longer active (expired OR soft-deleted).
     * A v5 entity referencing one of these via a shared {@code metadata_key_id}
     * needs its metadata re-encrypted under a fresh key.
     */
    private Set<String> inactiveKeyIds() {
        Set<String> inactive = new HashSet<>();
        for (MetadataKey key : metadataKeyRepository.findAll()) {
            if (key.getExpired() != null || key.getDeleted() != null) {
                inactive.add(key.getId());
            }
        }
        return inactive;
    }

    /**
     * PHP {@code findMetadataRotateKeyIndex} membership test: a v5 entity using a
     * shared metadata key whose referenced key is inactive (expired/deleted).
     */
    private boolean needsRotation(String metadata, String metadataKeyId,
            String metadataKeyType, Set<String> inactiveKeyIds) {
        return metadata != null
                && metadataKeyId != null
                && TYPE_SHARED_KEY.equals(metadataKeyType)
                && inactiveKeyIds.contains(metadataKeyId);
    }

    /**
     * PHP {@code MetadataBatchRotateKeyForm} + {@code IsSharedMetadataKeyUniqueActiveRule}:
     * the new key type must be {@code shared_key} and the new key must currently
     * be active (not deleted, not expired). A violation maps to the
     * {@code tooManyUpdatedEntities} 409.
     */
    private void assertNewKeyActiveSharedKey(String newKeyId, String newKeyType) {
        if (!TYPE_SHARED_KEY.equals(newKeyType)) {
            throw new PassboltApiException(HttpStatus.CONFLICT, "One or more entities were updated.");
        }
        MetadataKey key = metadataKeyRepository.findById(newKeyId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.CONFLICT,
                        "One or more entities were updated."));
        boolean active = key.getDeleted() == null && key.getExpired() == null;
        if (!active) {
            throw new PassboltApiException(HttpStatus.CONFLICT, "One or more entities were updated.");
        }
    }

    /** PHP {@code assertNotEmptyArrayData} + the MAX_PAGINATION_LIMIT batch guard. */
    private void assertBatch(List<MetadataRotateKeyDto.RotateRequest> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The request data should not be empty.");
        }
        if (entries.size() > MAX_BATCH_SIZE) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The request data is too large.");
        }
    }

    /**
     * Per-element validation mirroring PHP {@code MetadataBatchUpgradeForm}:
     * id/metadata/metadata_key_id/metadata_key_type are required;
     * id/metadata_key_id must be valid UUIDs. modified/modified_by are optional
     * for rotation (only checked for the conflict guard when present).
     */
    private void validateElement(MetadataRotateKeyDto.RotateRequest entry) {
        if (entry == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "The entity must be an array.");
        }
        if (!isUuid(entry.getId())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The identifier should be a valid UUID.");
        }
        if (isBlank(entry.getMetadata())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "A metadata is required.");
        }
        if (!isUuid(entry.getMetadataKeyId())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The metadata key identifier should be a valid UUID.");
        }
        if (isBlank(entry.getMetadataKeyType())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "A metadata key type is required.");
        }
    }

    /**
     * PHP {@code assertConflict}: when the client sends {@code modified}/
     * {@code modified_by}, they must match the stored row (else 409). Comparison
     * is to the second, matching PHP {@code toDateTimeString()}.
     */
    private void assertConflict(MetadataRotateKeyDto.RotateRequest entry,
            LocalDateTime storedModified, String storedModifiedBy) {
        if (entry.getModified() != null && storedModified != null
                && !storedModified.withNano(0).equals(entry.getModified().withNano(0))) {
            throw new PassboltApiException(HttpStatus.CONFLICT,
                    "The provided modified date does not match.");
        }
        if (entry.getModifiedBy() != null && storedModifiedBy != null
                && !storedModifiedBy.equals(entry.getModifiedBy())) {
            throw new PassboltApiException(HttpStatus.CONFLICT,
                    "The provided modified by does not match.");
        }
    }

    private PassboltApiException notFound(String id) {
        return new PassboltApiException(HttpStatus.NOT_FOUND, "Entity " + id + " not found.");
    }

    private static <T> List<T> paginate(List<T> all, int page, int size) {
        int effectiveSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_BATCH_SIZE);
        int from = Math.max(page, 0) * effectiveSize;
        if (from >= all.size()) {
            return List.of();
        }
        int to = Math.min(from + effectiveSize, all.size());
        return List.copyOf(all.subList(from, to));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
