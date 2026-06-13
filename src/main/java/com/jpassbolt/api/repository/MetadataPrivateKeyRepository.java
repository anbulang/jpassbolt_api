package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.MetadataPrivateKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link MetadataPrivateKey} entity operations.
 *
 * <p>
 * {@code user_id} is nullable (NULL = server key copy). App-level uniqueness on
 * {@code (metadata_key_id, user_id)} WHERE user_id IS NOT NULL is enforced at
 * the service layer using {@link #existsByMetadataKeyIdAndUserId}, since the DB
 * carries plain indexes only (no unique constraint, to allow the NULL slot).
 * </p>
 */
@Repository
public interface MetadataPrivateKeyRepository extends JpaRepository<MetadataPrivateKey, String> {

    /** All encrypted private-key copies for a given metadata key. */
    List<MetadataPrivateKey> findByMetadataKeyId(String metadataKeyId);

    /** All copies for a set of metadata keys (eager load for index contain). */
    List<MetadataPrivateKey> findByMetadataKeyIdIn(List<String> metadataKeyIds);

    /** A specific user's copy of a given metadata key's private key. */
    Optional<MetadataPrivateKey> findByMetadataKeyIdAndUserId(String metadataKeyId, String userId);

    /** The server copy (user_id IS NULL) of a given metadata key's private key. */
    Optional<MetadataPrivateKey> findByMetadataKeyIdAndUserIdIsNull(String metadataKeyId);

    /**
     * App-level uniqueness guard for {@code (metadata_key_id, user_id)} when
     * user_id is non-null (one copy per user per key).
     */
    boolean existsByMetadataKeyIdAndUserId(String metadataKeyId, String userId);

    /** All private-key copies owned by a user (e.g. user-deletion cleanup). */
    List<MetadataPrivateKey> findByUserId(String userId);
}
