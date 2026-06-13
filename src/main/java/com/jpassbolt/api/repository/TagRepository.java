package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Tag} (Passbolt EE "Tags" feature).
 *
 * <p>
 * Tags are hard-deleted (no soft-delete column on the tags table), so no
 * deleted-filtering finder is needed. The personal-vs-shared distinction is
 * driven by {@code is_shared} (and the matching {@code '#'} slug prefix).
 * </p>
 */
@Repository
public interface TagRepository extends JpaRepository<Tag, String> {

    /** All shared tags (slug begins with {@code '#'}), regardless of resource. */
    List<Tag> findByIsSharedTrue();

    /** Look up a shared tag by its (unique-by-convention) slug. */
    Optional<Tag> findBySlugAndIsSharedTrue(String slug);

    /** Look up an existing tag by exact slug + shared flag (for dedup on create). */
    Optional<Tag> findBySlugAndIsShared(String slug, Boolean isShared);

    /**
     * All tags currently visible to a user: every shared tag plus the personal
     * tags the user has attached to any resource (via {@code resources_tags}).
     * Ordered by slug to match the official Passbolt index ordering.
     */
    @Query("SELECT DISTINCT t FROM Tag t "
            + "LEFT JOIN ResourcesTag rt ON rt.tagId = t.id "
            + "WHERE t.isShared = true OR rt.userId = :userId "
            + "ORDER BY t.slug ASC")
    List<Tag> findVisibleToUser(@Param("userId") String userId);

    /**
     * Personal tags a user has attached to a specific resource (excludes shared
     * tags, which are not user-scoped).
     */
    @Query("SELECT DISTINCT t FROM Tag t "
            + "JOIN ResourcesTag rt ON rt.tagId = t.id "
            + "WHERE rt.resourceId = :resourceId AND rt.userId = :userId "
            + "AND t.isShared = false ORDER BY t.slug ASC")
    List<Tag> findPersonalTagsForResourceAndUser(@Param("resourceId") String resourceId,
            @Param("userId") String userId);

    /**
     * Every tag (shared + personal) attached to a resource, used when building a
     * resource's full tag list. Personal tags belonging to other users are
     * filtered at the service layer.
     */
    @Query("SELECT DISTINCT t FROM Tag t "
            + "JOIN ResourcesTag rt ON rt.tagId = t.id "
            + "WHERE rt.resourceId = :resourceId ORDER BY t.slug ASC")
    List<Tag> findByResourceId(@Param("resourceId") String resourceId);
}
