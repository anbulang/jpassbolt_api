package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.AuthenticationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthenticationTokenRepository extends JpaRepository<AuthenticationToken, String> {
    Optional<AuthenticationToken> findByToken(String token);

    Optional<AuthenticationToken> findByUserIdAndTypeAndActiveTrue(String userId, String type);

    /**
     * Setup start/complete token assertion: active register token belonging
     * to the user. Expiry (created + N days) is checked in the service layer
     * — the table has no expiry column.
     */
    Optional<AuthenticationToken> findByTokenAndUserIdAndTypeAndActiveTrue(String token, String userId, String type);
}
