package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.MetadataKeyDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.MetadataPrivateKey;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.MetadataPrivateKeyRepository;
import com.jpassbolt.api.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for the v5 (zero-knowledge) Metadata Keys domain — the
 * {@code /metadata/keys*} endpoints.
 *
 * <p>
 * Ported from the official Passbolt PHP implementation
 * ({@code plugins/PassboltCe/Metadata/src/Service/MetadataKey*} +
 * {@code Model/Table/MetadataKeysTable} / {@code MetadataPrivateKeysTable}).
 * The server is ZERO-KNOWLEDGE: it only STORES and FORWARDS armored blobs
 * (the metadata public key {@code armored_key}, and each user's encrypted copy
 * of the private key {@code data}). It never decrypts — only the parse-only
 * structural validations in {@link MetadataKeyValidationService} are applied.
 * </p>
 *
 * <p>
 * Key business rules carried over from the PHP reference:
 * <ul>
 *   <li>create requires admin (gated in the controller), at least one
 *       metadata_private_key, fingerprint uniqueness, the fingerprint must NOT
 *       be the server key or any user (gpgkeys) key, and at most 2 active keys
 *       may exist (the new one counted) — {@code MaxNoOfActiveMetadataKeysRule};</li>
 *   <li>each private key {@code data} blob must parse as an OpenPGP MESSAGE,
 *       its {@code user_id} (when present) must be an active, non-deleted user,
 *       and {@code (metadata_key_id, user_id)} must be unique (NULL user_id =
 *       the server copy, allowed once);</li>
 *   <li>"update" only marks a key expired: the fingerprint must match, the key
 *       must not be already deleted (404) nor already expired (400);</li>
 *   <li>"delete" soft-deletes a key (datetime, not boolean): the key must be
 *       expired first (400 otherwise) and must not still be in use by any
 *       resource/folder/tag (400 otherwise); its private keys are removed;</li>
 *   <li>private-key data update is owner-scoped (foreign / missing -> 404) and
 *       rejects a no-op re-edit by the same user (PHP "already edited").</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataKeyService {

    /** Maximum number of simultaneously active metadata keys (PHP rule). */
    public static final int MAX_ACTIVE_KEYS = 2;

    private final MetadataKeyRepository metadataKeyRepository;
    private final MetadataPrivateKeyRepository metadataPrivateKeyRepository;
    private final MetadataKeyValidationService validationService;
    private final GpgService gpgService;
    private final GpgKeyRepository gpgKeyRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    // ------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------

    /**
     * List metadata keys (PHP {@code MetadataKeysIndexService::get}).
     *
     * <p>
     * Filters mirror the PHP {@code filter[deleted]} / {@code filter[expired]}
     * query params: when a filter is supplied, {@code true} keeps rows where
     * the column IS NOT NULL and {@code false} keeps rows where it IS NULL;
     * when a filter is absent the column is not constrained. When neither
     * filter is supplied PHP returns every row (including deleted/expired).
     * </p>
     *
     * @param filterDeleted  optional deleted filter (null = unconstrained)
     * @param filterExpired  optional expired filter (null = unconstrained)
     * @param containPrivates when true, eagerly load this user's private-key
     *                        copies onto each returned key
     * @param userId         the requesting user (private-key copies are scoped
     *                       to this user, as in the PHP contain closure)
     * @return matching metadata keys, oldest first
     */
    @Transactional(readOnly = true)
    public List<MetadataKey> findKeys(Boolean filterDeleted, Boolean filterExpired,
            boolean containPrivates, String userId) {
        List<MetadataKey> keys = metadataKeyRepository.findAllByOrderByCreatedAsc().stream()
                .filter(k -> filterDeleted == null
                        || (filterDeleted ? k.getDeleted() != null : k.getDeleted() == null))
                .filter(k -> filterExpired == null
                        || (filterExpired ? k.getExpired() != null : k.getExpired() == null))
                .toList();
        return new ArrayList<>(keys);
    }

    /**
     * The current user's encrypted private-key copies for the given metadata
     * keys (used to populate the {@code metadata_private_keys} contain on the
     * index response — scoped to the requesting user, matching the PHP
     * contain closure {@code MetadataPrivateKeys.user_id = $userId}).
     *
     * @param metadataKeyIds the keys whose private copies are requested
     * @param userId         the requesting user
     * @return the user's private-key copies for those keys
     */
    @Transactional(readOnly = true)
    public List<MetadataPrivateKey> findUserPrivateKeysForKeys(List<String> metadataKeyIds, String userId) {
        if (metadataKeyIds.isEmpty()) {
            return List.of();
        }
        return metadataPrivateKeyRepository.findByMetadataKeyIdIn(metadataKeyIds).stream()
                .filter(pk -> userId != null && userId.equals(pk.getUserId()))
                .toList();
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    /**
     * Create a metadata key together with its per-user encrypted private-key
     * copies (PHP {@code MetadataKeyCreateService::create}).
     *
     * @param request the create request (fingerprint, armored public key, and
     *                at least one metadata_private_key)
     * @param userId  the acting admin's id (stored as created_by/modified_by)
     * @return the persisted metadata key with its private keys
     * @throws PassboltApiException 400 on any validation failure
     */
    @Transactional
    public MetadataKey createKey(MetadataKeyDto.CreateRequest request, String userId) {
        if (request == null) {
            throw badRequest("The metadata key data is required.");
        }

        String fingerprint = request.getFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            throw badRequest("A fingerprint is required.");
        }
        if (fingerprint.length() > 51) {
            throw badRequest("A fingerprint should not be greater than 51 characters.");
        }

        String armoredKey = request.getArmoredKey();
        if (armoredKey == null || armoredKey.isBlank()) {
            throw badRequest("An armored key is required.");
        }
        // Parse-only: the armored public key must be parsable and its
        // fingerprint must match the supplied fingerprint
        // (IsParsableArmoredKeyValidationRule + IsMatchingKeyFingerprintValidationRule).
        if (!validationService.isParsablePublicKey(armoredKey)) {
            throw badRequest("The armored key should be a valid OpenPGP public key.");
        }
        String parsedFingerprint;
        try {
            parsedFingerprint = validationService.extractFingerprint(armoredKey);
        } catch (IllegalArgumentException e) {
            throw badRequest("The armored key could not be parsed.");
        }
        if (!parsedFingerprint.equalsIgnoreCase(fingerprint)) {
            throw badRequest("The fingerprint does not match the armored key.");
        }

        List<MetadataKeyDto.PrivateKeyEntry> privateKeys = request.getMetadataPrivateKeys();
        if (privateKeys == null || privateKeys.isEmpty()) {
            throw badRequest("Need at least one metadata private key.");
        }

        // buildRules: fingerprint uniqueness.
        if (metadataKeyRepository.existsByFingerprint(fingerprint)) {
            throw badRequest("The fingerprint is already in use.");
        }
        // buildRules: must not reuse the server key fingerprint.
        if (fingerprint.equalsIgnoreCase(gpgService.getServerKeyFingerprint())) {
            throw badRequest("You cannot reuse the server keys.");
        }
        // buildRules: must not reuse any (non-deleted) user key fingerprint.
        if (gpgKeyRepository.findByFingerprintAndDeletedFalse(fingerprint).isPresent()) {
            throw badRequest("You cannot reuse the user key.");
        }
        // buildRules (create only): at most 2 active keys, counting the new one.
        if (metadataKeyRepository.countByDeletedIsNullAndExpiredIsNull() + 1 > MAX_ACTIVE_KEYS) {
            throw badRequest("Already two metadata keys are active.");
        }

        // Validate each private-key entry up front so we persist nothing on a
        // partial failure (PHP saves the whole graph atomically).
        for (MetadataKeyDto.PrivateKeyEntry entry : privateKeys) {
            validatePrivateKeyEntry(entry.getUserId(), entry.getData());
        }

        MetadataKey key = new MetadataKey();
        key.setFingerprint(fingerprint);
        key.setArmoredKey(armoredKey);
        key.setCreatedBy(userId);
        key.setModifiedBy(userId);
        MetadataKey saved = metadataKeyRepository.save(key);

        List<MetadataPrivateKey> savedPrivates = new ArrayList<>();
        for (MetadataKeyDto.PrivateKeyEntry entry : privateKeys) {
            MetadataPrivateKey pk = new MetadataPrivateKey();
            pk.setMetadataKeyId(saved.getId());
            pk.setUserId(entry.getUserId());
            pk.setData(entry.getData());
            pk.setCreatedBy(userId);
            pk.setModifiedBy(userId);
            savedPrivates.add(metadataPrivateKeyRepository.save(pk));
        }

        log.info("Metadata key {} created by user {} with {} private key(s)",
                saved.getId(), userId, savedPrivates.size());
        return saved;
    }

    // ------------------------------------------------------------------
    // Update (mark expired)
    // ------------------------------------------------------------------

    /**
     * Mark a metadata key as expired (PHP {@code MetadataKeyUpdateService}).
     * This is the only function of the PUT endpoint.
     *
     * @param metadataKeyId the key id (must be a valid UUID)
     * @param fingerprint   the fingerprint sent by the client; must match the
     *                      stored key's fingerprint
     * @param expired       the expiry timestamp to set (must be present)
     * @param userId        the acting admin's id (stored as modified_by)
     * @return the updated key
     * @throws PassboltApiException 400 invalid UUID / already expired / missing
     *         expiry, 404 not found / fingerprint mismatch / already deleted
     */
    @Transactional
    public MetadataKey markExpired(String metadataKeyId, String fingerprint,
            LocalDateTime expired, String userId) {
        if (!isUuid(metadataKeyId)) {
            throw badRequest("The metadata key ID should be a valid UUID.");
        }

        MetadataKey key = metadataKeyRepository.findById(metadataKeyId)
                .orElseThrow(() -> notFound("The metadata key does not exist or has been deleted."));

        if (fingerprint == null || !fingerprint.equals(key.getFingerprint())) {
            throw notFound("The metadata key fingerprint is invalid.");
        }
        if (key.getDeleted() != null) {
            throw notFound("The metadata key has already been deleted.");
        }
        if (key.getExpired() != null) {
            throw badRequest("The metadata key is already marked as expired.");
        }
        if (expired == null) {
            throw badRequest("A expired date is required.");
        }

        key.setExpired(expired);
        key.setModifiedBy(userId);
        MetadataKey saved = metadataKeyRepository.save(key);
        log.info("Metadata key {} marked expired by user {}", metadataKeyId, userId);
        return saved;
    }

    // ------------------------------------------------------------------
    // Delete (soft delete)
    // ------------------------------------------------------------------

    /**
     * Soft-delete a metadata key and remove its private-key copies
     * (PHP {@code MetadataKeyDeleteService}). The key must already be expired
     * and must not still be in use by any resource/folder/tag.
     *
     * @param metadataKeyId the key id (must be a valid UUID)
     * @param userId        the acting admin's id (stored as modified_by)
     * @throws PassboltApiException 400 invalid UUID / not expired / still in
     *         use, 404 not found / already deleted
     */
    @Transactional
    public void deleteKey(String metadataKeyId, String userId) {
        if (!isUuid(metadataKeyId)) {
            throw badRequest("The metadata key ID should be a valid UUID.");
        }

        MetadataKey key = metadataKeyRepository.findById(metadataKeyId)
                .orElseThrow(() -> notFound("The metadata key does not exist or has been deleted."));

        if (key.getDeleted() != null) {
            throw notFound("The metadata key has already been deleted.");
        }
        if (key.getExpired() == null) {
            throw badRequest("The metadata key should be marked as expired first.");
        }
        if (isKeyInUse(metadataKeyId)) {
            throw badRequest(
                    "The metadata key is still in use, migrate the remaining items to the new key first.");
        }

        key.setDeleted(LocalDateTime.now());
        key.setModifiedBy(userId);
        metadataKeyRepository.save(key);

        List<MetadataPrivateKey> privates = metadataPrivateKeyRepository.findByMetadataKeyId(metadataKeyId);
        if (!privates.isEmpty()) {
            metadataPrivateKeyRepository.deleteAll(privates);
        }
        log.info("Metadata key {} soft-deleted by user {} ({} private key(s) removed)",
                metadataKeyId, userId, privates.size());
    }

    // ------------------------------------------------------------------
    // Private keys: create / share missing
    // ------------------------------------------------------------------

    /**
     * Create (share) one or more missing per-user private-key copies
     * (PHP {@code MetadataPrivateKeysCreateService::createMany}, the
     * {@code POST /metadata/keys/privates.json} array body). All entries are
     * validated before any is persisted (PHP validates each entity first).
     *
     * @param entries the array of (metadata_key_id, user_id, data) entries
     * @param userId  the acting admin's id (stored as created_by/modified_by)
     * @throws PassboltApiException 400 / 404 on validation failure
     */
    @Transactional
    public void createPrivateKeys(List<MetadataKeyDto.CreatePrivatesRequest> entries, String userId) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        List<MetadataPrivateKey> toSave = new ArrayList<>();
        for (MetadataKeyDto.CreatePrivatesRequest entry : entries) {
            String metadataKeyId = entry.getMetadataKeyId();
            if (!isUuid(metadataKeyId)) {
                throw badRequest("The metadata key identifier should be a valid UUID.");
            }
            // The metadata key must exist and not be deleted.
            MetadataKey key = metadataKeyRepository.findById(metadataKeyId).orElse(null);
            if (key == null || key.getDeleted() != null) {
                throw notFound("The metadata key does not exist or has been deleted.");
            }
            validatePrivateKeyEntry(entry.getUserId(), entry.getData());
            assertPrivateKeyUnique(metadataKeyId, entry.getUserId());

            MetadataPrivateKey pk = new MetadataPrivateKey();
            pk.setMetadataKeyId(metadataKeyId);
            pk.setUserId(entry.getUserId());
            pk.setData(entry.getData());
            pk.setCreatedBy(userId);
            pk.setModifiedBy(userId);
            toSave.add(pk);
        }

        metadataPrivateKeyRepository.saveAll(toSave);
        log.info("{} metadata private key(s) created/shared by user {}", toSave.size(), userId);
    }

    // ------------------------------------------------------------------
    // Private keys: update data
    // ------------------------------------------------------------------

    /**
     * Update the encrypted data blob of one of the current user's private-key
     * copies (PHP {@code MetadataPrivateKeysUpdateService}). Owner-scoped: a
     * missing or foreign private key yields 404.
     *
     * @param privateKeyId the private key id (must be a valid UUID)
     * @param data         the new armored OpenPGP MESSAGE
     * @param userId       the owner (the private key's user_id must equal this)
     * @return the updated private key
     * @throws PassboltApiException 400 invalid UUID / data / re-edit, 404
     *         missing or foreign
     */
    @Transactional
    public MetadataPrivateKey updatePrivateKey(String privateKeyId, String data, String userId) {
        if (data == null || data.isBlank()) {
            throw badRequest("The request data is invalid.");
        }
        if (!isUuid(privateKeyId)) {
            throw badRequest("The request data is invalid.");
        }

        MetadataPrivateKey pk = metadataPrivateKeyRepository.findById(privateKeyId)
                .filter(p -> userId != null && userId.equals(p.getUserId()))
                .orElseThrow(() -> notFound(
                        "The metadata private key does not exist or has been deleted."));

        // PHP: reject a re-edit by the same user that last modified it.
        if (userId.equals(pk.getModifiedBy())) {
            throw badRequest("The metadata private key was already edited by the user.");
        }
        // Parse-only structural validation (zero-knowledge — never decrypt).
        if (!validationService.isParsableOpenPgpMessage(data)) {
            throw badRequest(
                    "The data is not valid. Please make sure it is encrypted for the correct key.");
        }

        pk.setData(data);
        pk.setModifiedBy(userId);
        MetadataPrivateKey saved = metadataPrivateKeyRepository.save(pk);
        log.info("Metadata private key {} updated by user {}", privateKeyId, userId);
        return saved;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Validate a single private-key entry (user_id format + existence/active,
     * data present + parsable OpenPGP MESSAGE). Mirrors the
     * MetadataPrivateKeysTable validation + build rules.
     */
    private void validatePrivateKeyEntry(String entryUserId, String data) {
        if (entryUserId != null && !isUuid(entryUserId)) {
            throw badRequest("The user identifier should be a valid UUID.");
        }
        if (data == null || data.isBlank()) {
            throw badRequest("A data is required.");
        }
        if (!validationService.isParsableOpenPgpMessage(data)) {
            throw badRequest("The data should be a valid ASCII-armored OpenPGP message.");
        }
        // user_id == null is the server copy and is allowed. A present user_id
        // must be an active, non-deleted user (UserIsActiveAndNotDeletedIfPresent).
        if (entryUserId != null) {
            boolean activeNotDeleted = userRepository.findById(entryUserId)
                    .map(u -> Boolean.TRUE.equals(u.getActive()) && !Boolean.TRUE.equals(u.getDeleted()))
                    .orElse(false);
            if (!activeNotDeleted) {
                throw badRequest("The user does not exist or is not active or is deleted.");
            }
        }
    }

    /**
     * App-level uniqueness on (metadata_key_id, user_id) where user_id is not
     * null; for the server copy (null user_id) only a single row is allowed
     * (UserAndMetadataKeyIdIsUniqueNullableCombo).
     */
    private void assertPrivateKeyUnique(String metadataKeyId, String entryUserId) {
        boolean exists = entryUserId != null
                ? metadataPrivateKeyRepository.existsByMetadataKeyIdAndUserId(metadataKeyId, entryUserId)
                : metadataPrivateKeyRepository.findByMetadataKeyIdAndUserIdIsNull(metadataKeyId).isPresent();
        if (exists) {
            throw badRequest("The metadata key is already shared with the user.");
        }
    }

    /**
     * True when the metadata key is still referenced by a non-deleted
     * resource, a folder, or a tag (PHP {@code MetadataKeyAssertUsageService}).
     * Uses lightweight JPQL count queries via the EntityManager so the keys
     * domain does not have to add finders to the shared resource/folder/tag
     * repositories (owned by the upgrade-rotate / tags domains).
     */
    private boolean isKeyInUse(String metadataKeyId) {
        long resources = entityManager.createQuery(
                        "SELECT COUNT(r) FROM Resource r WHERE r.metadataKeyId = :id AND r.deleted = false",
                        Long.class)
                .setParameter("id", metadataKeyId)
                .getSingleResult();
        if (resources > 0) {
            return true;
        }
        long folders = entityManager.createQuery(
                        "SELECT COUNT(f) FROM Folder f WHERE f.metadataKeyId = :id", Long.class)
                .setParameter("id", metadataKeyId)
                .getSingleResult();
        if (folders > 0) {
            return true;
        }
        long tags = entityManager.createQuery(
                        "SELECT COUNT(t) FROM Tag t WHERE t.metadataKeyId = :id", Long.class)
                .setParameter("id", metadataKeyId)
                .getSingleResult();
        return tags > 0;
    }

    private boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private PassboltApiException badRequest(String message) {
        return new PassboltApiException(HttpStatus.BAD_REQUEST, message);
    }

    private PassboltApiException notFound(String message) {
        return new PassboltApiException(HttpStatus.NOT_FOUND, message);
    }
}
