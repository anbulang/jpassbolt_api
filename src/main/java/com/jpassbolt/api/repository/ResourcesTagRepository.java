package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.ResourcesTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ResourcesTag} join rows (Passbolt EE "Tags" feature).
 *
 * <p>
 * Associations are hard-deleted. The {@code user_id} column is NULL for
 * shared-tag associations, so finders come in user-scoped (personal) and
 * shared-scoped variants.
 * </p>
 */
@Repository
public interface ResourcesTagRepository extends JpaRepository<ResourcesTag, String> {

    /** All tag associations for a resource (shared + every user's personal). */
    List<ResourcesTag> findByResourceId(String resourceId);

    /** A specific user's personal tag associations on a resource. */
    List<ResourcesTag> findByResourceIdAndUserId(String resourceId, String userId);

    /** Shared-tag associations on a resource ({@code user_id} IS NULL). */
    List<ResourcesTag> findByResourceIdAndUserIdIsNull(String resourceId);

    /** All associations referencing a tag (used to detect orphaned tags). */
    List<ResourcesTag> findByTagId(String tagId);

    /** Every personal tag association owned by a user (across all resources). */
    List<ResourcesTag> findByUserId(String userId);

    /**
     * Existing personal association for (resource, tag, user) — used to avoid
     * duplicate rows when re-tagging.
     */
    Optional<ResourcesTag> findByResourceIdAndTagIdAndUserId(String resourceId, String tagId, String userId);

    /**
     * Existing shared association for (resource, tag) where {@code user_id} IS
     * NULL — used to avoid duplicate shared-tag rows.
     */
    Optional<ResourcesTag> findByResourceIdAndTagIdAndUserIdIsNull(String resourceId, String tagId);

    /** Number of associations still referencing a tag (0 ⇒ tag is orphaned). */
    long countByTagId(String tagId);
}
