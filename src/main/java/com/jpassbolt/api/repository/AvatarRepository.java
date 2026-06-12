package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Avatar;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link Avatar} entities.
 * <p>
 * The read path of the avatars cluster only needs the inherited
 * {@code findById}. The avatars table has no {@code deleted} column, so no
 * {@code findByDeletedFalse()} is declared. Lookups by {@code profile_id}
 * belong to the upload path (users-crud cluster) and will be added there.
 */
public interface AvatarRepository extends JpaRepository<Avatar, String> {
}
