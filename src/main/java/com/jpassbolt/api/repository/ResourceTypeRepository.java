package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
     * Find all active (non soft-deleted) resource types whose slug is not in
     * the given collection. Used by the index endpoint to apply v4 semantics:
     * deleted IS NULL AND slug NOT IN (v5 slugs).
     */
    List<ResourceType> findByDeletedIsNullAndSlugNotIn(Collection<String> slugs);

    /**
     * Find a resource type by its unique slug (used by tests and seed data).
     */
    Optional<ResourceType> findBySlug(String slug);
}
