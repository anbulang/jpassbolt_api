package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.MetadataSessionKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link MetadataSessionKey} operations.
 *
 * Session keys are scoped per-user: every read/update/delete is restricted to
 * the current user's own rows. Session keys are hard-deleted (no soft-delete
 * column on the metadata_session_keys table), so no deleted-filtering finder is
 * needed.
 */
@Repository
public interface MetadataSessionKeyRepository extends JpaRepository<MetadataSessionKey, String> {

    /**
     * Find all session keys belonging to a user, most recently modified first
     * (matches the official Passbolt index ordering).
     */
    List<MetadataSessionKey> findByUserIdOrderByModifiedDesc(String userId);

    /**
     * Find a session key by its id only if it belongs to the given user.
     * Used to scope update/delete to the owner: a foreign or missing id yields
     * an empty Optional (the controller maps that to 404).
     */
    Optional<MetadataSessionKey> findByIdAndUserId(String id, String userId);
}
