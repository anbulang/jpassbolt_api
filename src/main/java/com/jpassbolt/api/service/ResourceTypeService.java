package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.repository.ResourceTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for read-only resource type operations.
 *
 * <p>Ported from the PHP reference implementation
 * ({@code plugins/PassboltCe/ResourceTypes}):</p>
 * <ul>
 *   <li>index: v4 semantics — active types only (deleted IS NULL) excluding
 *       v5 slugs (ResourceTypesFinderService + notDeleted finder).</li>
 *   <li>view: mirrors CakePHP {@code Table::get()} — direct lookup by id with
 *       NO deleted/v5 filtering; soft-deleted and v5 rows are returned as-is.
 *       This asymmetry with index is intentional, do not "fix" it.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceTypeService {

    private final ResourceTypeRepository resourceTypeRepository;

    /**
     * Get all active v4 resource types: deleted IS NULL and slug not in the
     * v5 slug list. Used by GET /resource-types.json.
     */
    @Transactional(readOnly = true)
    public List<ResourceType> getResourceTypes() {
        return resourceTypeRepository.findByDeletedIsNullAndSlugNotIn(ResourceType.V5_RESOURCE_TYPE_SLUGS);
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
}
