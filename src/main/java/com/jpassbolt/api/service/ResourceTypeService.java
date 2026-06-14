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
 *   <li>index: active types only (deleted IS NULL), v4 AND v5 — matches the PHP
 *       ResourceTypesIndexController with {@code passbolt.v5.enabled=true} (the
 *       default): no slug-version filter; the client gates creation via
 *       /metadata/types/settings.</li>
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
}
