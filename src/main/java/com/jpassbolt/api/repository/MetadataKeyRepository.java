package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.MetadataKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link MetadataKey} entity operations.
 *
 * <p>
 * Soft-delete and expiry are datetime columns (NOT booleans): a metadata key is
 * "active" when both {@code deleted} and {@code expired} are NULL. Finders here
 * expose the building blocks used by the keys-domain service for active-key
 * filtering, fingerprint uniqueness, and the "max 2 active keys" rule.
 * </p>
 */
@Repository
public interface MetadataKeyRepository extends JpaRepository<MetadataKey, String> {

    /**
     * All active keys (not deleted, not expired), oldest first.
     * Active = {@code deleted IS NULL AND expired IS NULL}.
     */
    List<MetadataKey> findByDeletedIsNullAndExpiredIsNullOrderByCreatedAsc();

    /**
     * All not-deleted keys (deleted IS NULL), including expired ones.
     * Used by the index endpoint when filterExpired is not requested.
     */
    List<MetadataKey> findByDeletedIsNullOrderByCreatedAsc();

    /**
     * All keys, including soft-deleted, oldest first
     * (index endpoint with filterDeleted=false to include deleted rows).
     */
    List<MetadataKey> findAllByOrderByCreatedAsc();

    /**
     * Count of currently active keys — used to enforce the "max 2 active
     * metadata keys" creation rule at the service layer.
     */
    long countByDeletedIsNullAndExpiredIsNull();

    /**
     * Fingerprint uniqueness check (the DB also has a UNIQUE index, but the
     * service rejects reuse before insert to return a clean 400).
     */
    boolean existsByFingerprint(String fingerprint);

    Optional<MetadataKey> findByFingerprint(String fingerprint);

    /**
     * Active shared metadata key existence check (active = {@code deleted IS NULL
     * AND expired IS NULL}). Used for the {@code IsValidEncryptedMetadata}
     * shared-key resolution and the {@code MetadataKeyIdNotExpiredRule} shared
     * branch (PHP: {@code MetadataKeys.exists({id, expired IS NULL})}; the
     * additional {@code deleted IS NULL} keeps a soft-deleted shared key from
     * being treated as usable). Sole caller is {@code MetadataValidationSupport}
     * (foundation). Additive derived query — no schema impact.
     */
    boolean existsByIdAndDeletedIsNullAndExpiredIsNull(String id);
}
