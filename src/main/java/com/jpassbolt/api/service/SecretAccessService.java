package com.jpassbolt.api.service;

import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.SecretAccess;
import com.jpassbolt.api.repository.SecretAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Records secret-read accesses into the {@code secret_accesses} audit table.
 *
 * <p>Port of the PHP {@code SecretAccessesTable::createFromSecretEntity /
 * createFromSecretDetails} write path, called from the resource/secret view
 * controllers exactly where PHP calls {@code _logSecretAccesses}.</p>
 *
 * <p><b>Best-effort by design.</b> Auditing a read must never break the read: if the
 * insert fails (e.g. a transient DB error) we log and return rather than propagating a
 * 500 to a caller who legitimately retrieved their password. This is a deliberate,
 * documented deviation from PHP, which throws {@code InternalErrorException} on a failed
 * audit save. The view controllers are not {@code @Transactional}, so each
 * {@code save()} commits in its own short transaction in isolation; no ambient
 * transaction is poisoned by a swallowed failure.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecretAccessService {

    private final SecretAccessRepository secretAccessRepository;

    /**
     * Record that {@code userId} read the secret {@code secretId} of resource
     * {@code resourceId}. Best-effort; never throws.
     */
    public void logAccess(String userId, String resourceId, String secretId) {
        if (userId == null || resourceId == null || secretId == null) {
            return;
        }
        try {
            secretAccessRepository.save(new SecretAccess(userId, resourceId, secretId));
        } catch (Exception e) {
            // Auditing is best-effort — the caller already got their secret.
            log.error("Failed to record secret access (user={}, resource={}, secret={}): {}",
                    userId, resourceId, secretId, e.getMessage());
        }
    }

    /**
     * Record an access for a {@link Secret} entity (resource id + secret id read off it).
     * Mirrors PHP {@code createFromSecretEntity}.
     */
    public void logAccess(String userId, Secret secret) {
        if (secret == null) {
            return;
        }
        logAccess(userId, secret.getResourceId(), secret.getId());
    }

    /**
     * Record an access for the caller's own secret within a resource's secret list.
     *
     * <p>Used by {@code GET /resources/{id}.json}, which embeds the resource's secrets.
     * Only the caller's own secret (the ciphertext they can actually decrypt) is a
     * meaningful "access" — matching PHP, whose resource view contains only the
     * requesting user's secret. If the caller has no secret in the list (should not
     * happen once READ permission is granted), nothing is recorded.</p>
     */
    public void logCallerSecretAccess(String userId, List<Secret> secrets) {
        if (userId == null || secrets == null) {
            return;
        }
        secrets.stream()
                .filter(s -> userId.equals(s.getUserId()))
                .findFirst()
                .ifPresent(s -> logAccess(userId, s));
    }
}
