package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.MetadataSessionKey;
import com.jpassbolt.api.repository.MetadataSessionKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Service for the v5 metadata <em>session keys</em> — the per-user cache of
 * OpenPGP-encrypted session key blobs.
 *
 * <p>Ported from the official Passbolt PHP services under
 * {@code plugins/PassboltCe/Metadata/src/Service/MetadataSessionKey*}:</p>
 * <ul>
 *   <li>{@link #findByUser(String)} — {@code MetadataSessionKeysGetService}:
 *       returns only the current user's session keys.</li>
 *   <li>{@link #create(String, String)} — {@code MetadataSessionKeyCreateService}:
 *       validates the blob is a parsable asymmetric OpenPGP MESSAGE
 *       (store-and-forward, never decrypted) then persists it for the user.</li>
 *   <li>{@link #update(String, String, String, LocalDateTime)} —
 *       {@code MetadataSessionKeyUpdateService}: ownership-scoped, with an
 *       optimistic-lock check (409 when the client's {@code modified} timestamp
 *       does not match the stored one) and a 400 "no changes" guard.</li>
 *   <li>{@link #delete(String, String)} — {@code MetadataSessionKeyDeleteService}:
 *       ownership-scoped hard delete (no soft-delete column).</li>
 * </ul>
 *
 * <p><strong>Zero-knowledge:</strong> the server only stores and transfers the
 * armored ciphertext in {@code data}; it never decrypts it. Validation is the
 * parse-only check performed by {@link MetadataSessionKeyValidationService}
 * (Bouncy Castle, parse-only — Iron Law 1).</p>
 *
 * <p><strong>Ownership scoping:</strong> every read/update/delete is restricted
 * to the requesting user's own rows. A foreign or missing id yields 404 (the
 * key's existence is never disclosed), matching the PHP
 * {@code firstOrFail() -> NotFoundException} behavior.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataSessionKeyService {

    private final MetadataSessionKeyRepository metadataSessionKeyRepository;
    private final MetadataSessionKeyValidationService validationService;

    /**
     * List the current user's session keys, most recently modified first.
     *
     * @param userId the requesting user's id
     * @return the user's session keys (never another user's)
     */
    @Transactional(readOnly = true)
    public List<MetadataSessionKey> findByUser(String userId) {
        return metadataSessionKeyRepository.findByUserIdOrderByModifiedDesc(userId);
    }

    /**
     * Create a session key for the current user.
     *
     * <p>The {@code data} blob is validated as a parsable single-recipient
     * asymmetric OpenPGP MESSAGE (never decrypted), then stored. Mirrors
     * {@code MetadataSessionKeyCreateService::create}.</p>
     *
     * @param userId the owning user's id
     * @param data   the armored OpenPGP MESSAGE
     * @return the persisted session key
     * @throws PassboltApiException 400 when {@code data} is missing or not a
     *                              valid encrypted message
     */
    @Transactional
    public MetadataSessionKey create(String userId, String data) {
        validationService.assertValidEncryptedSessionKey(data);

        MetadataSessionKey sessionKey = new MetadataSessionKey();
        sessionKey.setUserId(userId);
        sessionKey.setData(data);

        MetadataSessionKey saved = metadataSessionKeyRepository.save(sessionKey);
        log.debug("Metadata session key {} created for user {}", saved.getId(), userId);
        return saved;
    }

    /**
     * Update the current user's session key data, guarding against concurrent
     * edits with an optimistic-lock check.
     *
     * <p>Mirrors {@code MetadataSessionKeyUpdateService::update}:</p>
     * <ol>
     *   <li>resolve the key scoped to the user (foreign/missing -> 404);</li>
     *   <li>validate the new {@code data} (parse-only, 400 on failure);</li>
     *   <li>reject an identical {@code data} payload (400 "no changes");</li>
     *   <li>compare the client's {@code expectedModified} to the stored
     *       {@code modified} at second precision — mismatch -> 409;</li>
     *   <li>persist the new data (a fresh {@code modified} is stamped by
     *       {@code @PreUpdate}).</li>
     * </ol>
     *
     * @param sessionKeyId     the session key id
     * @param userId           the requesting (owning) user's id
     * @param data             the new armored OpenPGP MESSAGE
     * @param expectedModified the client's last-known {@code modified} timestamp
     * @return the updated session key
     * @throws PassboltApiException 404 if not found / not the owner,
     *                              400 on validation failure or identical data,
     *                              409 on optimistic-lock mismatch
     */
    @Transactional
    public MetadataSessionKey update(String sessionKeyId, String userId, String data,
                                     LocalDateTime expectedModified) {
        MetadataSessionKey sessionKey = metadataSessionKeyRepository
                .findByIdAndUserId(sessionKeyId, userId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The metadata session key does not exist or does not belong to this user."));

        validationService.assertValidEncryptedSessionKey(data);

        // 400 no changes to be made (PHP: $data['data'] === $entity->get('data')).
        if (data.equals(sessionKey.getData())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The metadata session key data is identical.");
        }

        // 409 optimistic lock: PHP compares the two datetimes at second precision
        // (DateTime->diffInSeconds(...) === 0).
        if (!modifiedMatches(expectedModified, sessionKey.getModified())) {
            throw new PassboltApiException(HttpStatus.CONFLICT,
                    "The metadata session key data has changed.");
        }

        sessionKey.setData(data);
        MetadataSessionKey updated = metadataSessionKeyRepository.save(sessionKey);
        log.debug("Metadata session key {} updated for user {}", sessionKeyId, userId);
        return updated;
    }

    /**
     * Hard delete the current user's session key. A foreign or missing id
     * yields 404 (existence is not disclosed). Mirrors
     * {@code MetadataSessionKeyDeleteService::delete}.
     *
     * @param sessionKeyId the session key id
     * @param userId       the requesting (owning) user's id
     * @throws PassboltApiException 404 if not found / not the owner
     */
    @Transactional
    public void delete(String sessionKeyId, String userId) {
        MetadataSessionKey sessionKey = metadataSessionKeyRepository
                .findByIdAndUserId(sessionKeyId, userId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The metadata session key does not exist or does not belong to this user."));

        metadataSessionKeyRepository.delete(sessionKey);
        log.debug("Metadata session key {} deleted for user {}", sessionKeyId, userId);
    }

    /**
     * Optimistic-lock comparison at second precision, matching the PHP
     * {@code diffInSeconds(...) === 0} check. A null client value never matches.
     */
    private boolean modifiedMatches(LocalDateTime expectedModified, LocalDateTime storedModified) {
        if (expectedModified == null || storedModified == null) {
            return false;
        }
        return expectedModified.truncatedTo(ChronoUnit.SECONDS)
                .equals(storedModified.truncatedTo(ChronoUnit.SECONDS));
    }
}
