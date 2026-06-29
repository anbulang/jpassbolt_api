package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.SecretAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the append-only {@code secret_accesses} audit table.
 *
 * <p>The derived finders exist mainly for the Activity-log read side (not exposed in
 * CE) and for tests asserting that an access was recorded. Writes go through
 * {@link com.jpassbolt.api.service.SecretAccessService}.</p>
 */
@Repository
public interface SecretAccessRepository extends JpaRepository<SecretAccess, String> {

    List<SecretAccess> findByResourceId(String resourceId);

    List<SecretAccess> findByUserIdAndResourceId(String userId, String resourceId);

    List<SecretAccess> findBySecretId(String secretId);

    long countByUserIdAndResourceId(String userId, String resourceId);
}
