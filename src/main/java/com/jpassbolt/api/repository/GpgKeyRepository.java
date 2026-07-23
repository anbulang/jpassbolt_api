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
     * Whole-table fingerprint uniqueness probe, INCLUDING soft-deleted rows
     * (PHP {@code GpgkeysTable::buildRules} {@code isUnique(['fingerprint'])}
     * does not exclude them either). One key pair maps to exactly one account,
     * ever — JwtAuthService verifies a login challenge against the key stored
     * on the claimed user's row, so a duplicated fingerprint would let one
     * private key authenticate as two users. Safe against pre-existing
     * duplicate rows (unlike the Optional finders, which would throw
     * IncorrectResultSizeDataAccessException).
     */
    boolean existsByFingerprint(String fingerprint);

    /**
     * All rows sharing a fingerprint regardless of deleted flag. Used by the
     * seeder to detect (and refuse to widen) fingerprint collisions even when
     * the table already holds dirty duplicate data.
     */
    List<GpgKey> findAllByFingerprint(String fingerprint);

    /**
     * Find a GPG key by key ID for active (non-deleted) keys only.
     */
    Optional<GpgKey> findByKeyIdAndDeletedFalse(String keyId);

    /**
     * Account-recovery completion (PHP {@code GpgkeysTable::getByFingerprintAndUserId}):
     * the submitted public key's fingerprint must match the user's already-stored,
     * non-deleted key. Used by RecoverService to reject a key that does not belong
     * to the recovering user.
     */
    Optional<GpgKey> findByFingerprintAndUserIdAndDeletedFalse(String fingerprint, String userId);

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

    /**
     * v5 metadata-key expiry rule (PHP {@code MetadataKeyIdNotExpiredRule},
     * {@code user_key} branch): a personal GPG key is usable as a metadata key
     * only while it has NOT expired, i.e. {@code Gpgkeys.expires IS NULL}.
     * Sole caller is {@code MetadataValidationSupport} (foundation). Additive
     * Spring Data derived query — no schema impact.
     */
    boolean existsByIdAndExpiresIsNull(String id);
}
