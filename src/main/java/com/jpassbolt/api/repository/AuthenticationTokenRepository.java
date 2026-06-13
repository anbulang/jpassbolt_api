package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.AuthenticationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * Refresh token lookup, cookie path — no user id available (PHP
     * RefreshTokenAbstractService::queryRefreshToken).
     */
    Optional<AuthenticationToken> findByTokenAndType(String token, String type);

    /**
     * Refresh token lookup, payload path (PHP queryRefreshTokenWithUserId).
     */
    Optional<AuthenticationToken> findByTokenAndTypeAndUserId(String token, String type, String userId);

    /**
     * All active tokens of a type for a user, list version: a user routinely
     * holds several active refresh tokens (multi-device), the Optional
     * variant above would throw IncorrectResultSizeDataAccessException.
     * Used by the JWT logout "revoke all sessions" branch.
     */
    List<AuthenticationToken> findAllByUserIdAndTypeAndActiveTrue(String userId, String type);

    /**
     * Verify token replay protection (PHP
     * VerifyTokenValidationService::validateNonce): the (token, user_id,
     * type='verify_token') triple must never have been seen before.
     */
    boolean existsByTokenAndUserIdAndType(String token, String userId, String type);

    /**
     * Physically delete expired tokens of a type (PHP
     * VerifyTokenCreateService cleanup uses deleteAll — a hard delete, the
     * table would grow unbounded otherwise). Caller must be @Transactional.
     */
    @Modifying
    @Query("DELETE FROM AuthenticationToken t WHERE t.type = :type AND t.created < :threshold")
    void deleteByTypeAndCreatedBefore(@Param("type") String type, @Param("threshold") LocalDateTime threshold);

    /** All tokens of a type for a user (active or not) — MFA bulk invalidation. */
    List<AuthenticationToken> findByUserIdAndType(String userId, String type);
}
