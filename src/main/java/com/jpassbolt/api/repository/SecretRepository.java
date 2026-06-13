package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Secret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SecretRepository extends JpaRepository<Secret, String> {

    List<Secret> findByResourceId(String resourceId);

    Optional<Secret> findByResourceIdAndUserId(String resourceId, String userId);

    /**
     * Batch lookup of the existing secrets of a resource for a set of users —
     * lets the share endpoint update already-present ciphertexts in one query
     * (PHP SecretsUpdateSecretsService::updateSecret updates, it does not
     * skip). Caller must pass a non-empty collection.
     */
    List<Secret> findByResourceIdAndUserIdIn(String resourceId, Collection<String> userIds);

    /**
     * Lost-access cascade (PHP ResourcesTable::deleteLostAccessSecrets):
     * select-then-delete every secret of the resource whose user is NOT in
     * the post-change access set. The NOT IN semantics naturally merge access
     * paths — a user keeping a direct permission or another permitted group
     * membership is in the "after" set and never selected here.
     * ⚠ Caller MUST guarantee the collection is non-empty (an empty NOT IN is
     * illegal/always-false SQL); the empty case is handled by the service via
     * {@link #findByResourceId} full deletion.
     */
    List<Secret> findByResourceIdAndUserIdNotIn(String resourceId, Collection<String> userIds);

    void deleteByResourceId(String resourceId);

    List<Secret> findByUserId(String userId);

    /**
     * Hard-delete every secret of a user (user deletion cascade).
     * Caller must be @Transactional.
     */
    void deleteByUserId(String userId);
}
