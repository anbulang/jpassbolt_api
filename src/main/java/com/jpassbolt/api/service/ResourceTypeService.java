package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.ResourceTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Service for resource type operations.
 *
 * <p>Ported from the PHP reference implementation
 * ({@code plugins/PassboltCe/ResourceTypes}):</p>
 * <ul>
 *   <li>index: active types only (deleted IS NULL), v4 AND v5 — matches the PHP
 *       ResourceTypesIndexController with {@code passbolt.v5.enabled=true} (the
 *       default): no slug-version filter; the client gates creation via
 *       /metadata/types/settings.</li>
 *   <li>view: mirrors CakePHP {@code Table::get()} — direct lookup by id with
 *       NO deleted/v5 filtering; soft-deleted and v5 rows are returned as-is.
 *       This asymmetry with index is intentional, do not "fix" it.</li>
 *   <li>delete / restore: soft-delete and undo-delete, ported from PHP
 *       {@code ResourceTypesDeleteService} (delete/undoDelete) and
 *       {@code ResourceTypesIsTheLastOneCheckService}. Both are admin-gated at
 *       the controller; the guards here mirror the reference exactly.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceTypeService {

    /** v4 resource type slug family, used by the "last of default version" guard. */
    private static final List<String> V4_SLUGS = List.of(
            ResourceType.SLUG_PASSWORD_AND_DESCRIPTION,
            ResourceType.SLUG_PASSWORD_DESCRIPTION_TOTP,
            ResourceType.SLUG_PASSWORD_STRING,
            ResourceType.SLUG_STANDALONE_TOTP);

    private final ResourceTypeRepository resourceTypeRepository;
    private final ResourceRepository resourceRepository;
    private final MetadataTypesSettingsService metadataTypesSettingsService;

    /**
     * Get all active resource types (deleted IS NULL), ordered by slug. Mirrors
     * the PHP ResourceTypesIndexController with {@code passbolt.v5.enabled=true}
     * (the default): both v4 and v5 types are returned; only soft-deleted rows
     * are excluded. The client decides which to OFFER for creation via
     * /metadata/types/settings. Used by GET /resource-types.json.
     */
    @Transactional(readOnly = true)
    public List<ResourceType> getResourceTypes() {
        return resourceTypeRepository.findByDeletedIsNullOrderBySlugAsc();
    }

    /**
     * Get a single resource type by id without any deleted/v5 filtering
     * (PHP Table::get() semantics). Used by GET /resource-types/{id}.json.
     *
     * @throws PassboltApiException 404 if no row exists for the given id
     */
    @Transactional(readOnly = true)
    public ResourceType getResourceTypeById(String id) {
        return resourceTypeRepository.findById(id)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The resource type does not exist."));
    }

    /**
     * Soft-delete a resource type. Port of PHP
     * {@code ResourceTypesDeleteService::delete}; the validation order is
     * preserved exactly: UUID, existence, already-deleted, last-of-default-version,
     * last-one-overall, then still-in-use. Used by DELETE /resource-types/{id}.json
     * (admin-gated in the controller).
     *
     * @throws PassboltApiException 400 (bad UUID / already deleted / last of its
     *         default version / the only one left / still referenced by resources)
     *         or 404 (not found)
     */
    @Transactional
    public void deleteResourceType(String id) {
        ResourceType resourceType = findForWrite(id);

        if (resourceType.getDeleted() != null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The resource type is already deleted.");
        }
        if (isLastOfTheDefaultVersion(resourceType)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "You cannot delete the last resource type of the default version.");
        }
        if (resourceTypeRepository.count() < 2) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "You cannot delete the last resource type available.");
        }
        if (resourceRepository.countByResourceTypeIdAndDeletedFalse(id) != 0) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The resource type can not be deleted as resources of this type still exist.");
        }

        resourceType.setDeleted(LocalDateTime.now(ZoneOffset.UTC));
        resourceTypeRepository.save(resourceType);
    }

    /**
     * Undo a soft-delete (restore) a resource type. Port of PHP
     * {@code ResourceTypesDeleteService::undoDelete}. Used by
     * PUT /resource-types/{id}.json with body {@code {"deleted": null}}
     * (admin-gated in the controller).
     *
     * @throws PassboltApiException 400 (bad UUID / not currently deleted) or
     *         404 (not found)
     */
    @Transactional
    public void restoreResourceType(String id) {
        ResourceType resourceType = findForWrite(id);

        if (resourceType.getDeleted() == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The resource type is not deleted.");
        }

        resourceType.setDeleted(null);
        resourceTypeRepository.save(resourceType);
    }

    /**
     * UUID check (400) before lookup (404), matching the PHP services which call
     * {@code Validation::uuid()} before {@code firstOrFail()}.
     */
    private ResourceType findForWrite(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The resource type identifier should be a UUID.");
        }
        return resourceTypeRepository.findById(id)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The resource type does not exist."));
    }

    /**
     * Port of PHP {@code ResourceTypesIsTheLastOneCheckService::isLastOfTheDefaultVersion}:
     * a resource type may not be deleted if it is the last remaining one of the
     * current default version family (v4 vs v5, per metadata types settings).
     * When the type belongs to a different version family than the default,
     * the rule does not apply (returns false). The count includes soft-deleted
     * rows, matching the PHP finder which applies no deleted condition.
     */
    private boolean isLastOfTheDefaultVersion(ResourceType resourceType) {
        String defaultVersion = metadataTypesSettingsService.getTypesSettings().getDefaultResourceTypes();
        boolean isV4Type = V4_SLUGS.contains(resourceType.getSlug());
        List<ResourceType> all = resourceTypeRepository.findAll();

        long familyCount;
        if (MetadataTypesSettingsService.V4.equals(defaultVersion)) {
            if (!isV4Type) {
                return false;
            }
            familyCount = all.stream().filter(t -> V4_SLUGS.contains(t.getSlug())).count();
        } else {
            if (isV4Type) {
                return false;
            }
            familyCount = all.stream().filter(t -> !V4_SLUGS.contains(t.getSlug())).count();
        }
        return familyCount < 2;
    }
}
