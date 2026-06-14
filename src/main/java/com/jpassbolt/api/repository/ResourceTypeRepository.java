package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ResourceType} entities.
 *
 * <p>Note: resource_types uses a nullable DATETIME soft-delete column
 * (null = active), hence {@code DeletedIsNull} instead of the
 * {@code DeletedFalse} pattern used by Resource/User/GpgKey.</p>
 */
@Repository
public interface ResourceTypeRepository extends JpaRepository<ResourceType, String> {

    /**
     * Find all active (non soft-deleted) resource types, ordered by slug for a
     * deterministic response. Used by the index endpoint, which mirrors the PHP
     * reference with {@code passbolt.v5.enabled=true} (the default): return every
     * active type (v4 AND v5), excluding only soft-deleted rows. There is no
     * slug-version filter — the client decides what to offer for creation via
     * {@code /metadata/types/settings}.
     */
    List<ResourceType> findByDeletedIsNullOrderBySlugAsc();

    /**
     * Find a resource type by its unique slug (used by tests and seed data).
     */
    Optional<ResourceType> findBySlug(String slug);
}
