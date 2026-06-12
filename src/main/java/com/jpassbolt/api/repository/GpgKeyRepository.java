package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.GpgKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GpgKeyRepository extends JpaRepository<GpgKey, String> {
    List<GpgKey> findByUserId(String userId);

    Optional<GpgKey> findByKeyId(String keyId);

    Optional<GpgKey> findByFingerprint(String fingerprint);

    /**
     * Find a GPG key by fingerprint for active (non-deleted) keys only.
     * Used during authentication to identify users.
     */
    Optional<GpgKey> findByFingerprintAndDeletedFalse(String fingerprint);

    /**
     * Find a GPG key by key ID for active (non-deleted) keys only.
     */
    Optional<GpgKey> findByKeyIdAndDeletedFalse(String keyId);

    /**
     * List keys by deleted flag. Used by the /gpgkeys.json index endpoint
     * (filter[is-deleted], defaults to false).
     */
    List<GpgKey> findByDeleted(boolean deleted);

    /**
     * List keys by deleted flag whose modified timestamp is STRICTLY greater
     * than the given bound (Spring Data "After" = greater-than), replicating
     * the PHP findIndex "modified > X" semantics for filter[modified-after].
     */
    List<GpgKey> findByDeletedAndModifiedAfter(boolean deleted, LocalDateTime modifiedAfter);
}
