package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Secret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecretRepository extends JpaRepository<Secret, String> {

    List<Secret> findByResourceId(String resourceId);

    Optional<Secret> findByResourceIdAndUserId(String resourceId, String userId);

    void deleteByResourceId(String resourceId);

    List<Secret> findByUserId(String userId);

    /**
     * Hard-delete every secret of a user (user deletion cascade).
     * Caller must be @Transactional.
     */
    void deleteByUserId(String userId);
}
