package com.jpassbolt.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.JwtAuthDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JWT authentication channel business logic, ported from the PHP
 * {@code GpgJwtAuthenticator} + {@code JwtArmoredChallengeService} +
 * {@code RefreshToken*Service} + {@code VerifyToken*Service}.
 *
 * <p>
 * Login flow: the client sends an armored PGP challenge encrypted with the
 * server public key, carrying
 * {@code {version, domain, verify_token, verify_token_expiry}}. The server
 * validates every field, stores the verify token (replay protection), issues
 * an RS256 access token plus a rotating refresh token, and answers with a
 * challenge encrypted with the user public key carrying
 * {@code {version, domain, access_token, refresh_token, verify_token}}.
 * </p>
 *
 * <p>
 * Signature handling matches PHP: the client challenge MUST be signed with
 * the user key ({@code gpg->decrypt(..., verify: true)} — an unsigned or
 * badly signed challenge is rejected with "The user signature could not be
 * verified."), and the response challenge is encrypted with the user key AND
 * signed with the server key ({@code gpg->encryptSign(...)}), restoring the
 * mutual authentication guarantee of the GpgJwtAuthenticator.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtAuthService {

    /** PHP GpgJwtAuthenticator::PROTOCOL_VERSION */
    public static final String PROTOCOL_VERSION = "1.0.0";

    /**
     * Token type constants, mirroring the PHP AuthenticationToken entity
     * (TYPE_REFRESH_TOKEN / TYPE_VERIFY_TOKEN). Both fit the varchar(16)
     * column of authentication_tokens.type.
     */
    public static final String TYPE_REFRESH_TOKEN = "refresh_token";
    public static final String TYPE_VERIFY_TOKEN = "verify_token";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private final GpgService gpgService;
    private final JwtService jwtService;
    private final AuthenticationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final GpgKeyRepository gpgKeyRepository;
    private final ObjectMapper objectMapper;

    /** Domain claim — PHP Router::url('/', true). Same source as JwtService.iss. */
    @Value("${jpassbolt.settings.full-base-url:http://localhost:8080}")
    private String fullBaseUrl;

    /** PHP passbolt.auth.token.refresh_token.expiry default '1 month'. */
    @Value("${jpassbolt.jwt.refresh-token-expiry-days:30}")
    private long refreshTokenExpiryDays;

    /** PHP passbolt.auth.token.verify_token.expiry default '1 hour'. */
    @Value("${jpassbolt.jwt.verify-token-expiry-hours:1}")
    private long verifyTokenExpiryHours;

    /**
     * Result of a login or a refresh token rotation.
     */
    public record RefreshResult(String accessToken, String refreshToken, long refreshTokenExpiryDays) {
    }

    /**
     * POST /auth/jwt/login.json core. The validation order strictly follows
     * PHP GpgJwtAuthenticator::init() + verifyChallenge().
     *
     * @param userId           user UUID from the request body
     * @param armoredChallenge armored PGP challenge from the request body
     * @return the armored, encrypted response challenge
     */
    @Transactional
    public String login(String userId, String armoredChallenge) {
        // assertUserId (PHP BadRequest -> 400)
        if (!isUuid(userId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user id is missing or invalid.");
        }

        // findUser: must exist, be active, not deleted, not disabled (404)
        User user = userRepository.findById(userId)
                .filter(u -> u.getActive() && !u.getDeleted() && u.getDisabled() == null)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The user does not exist or is not active or has been deleted."));

        // assertUserData / setUserKey: the user needs a usable OpenPGP key
        GpgKey gpgKey = gpgKeyRepository.findByUserId(userId).stream()
                .filter(k -> !k.getDeleted())
                .findFirst()
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The user OpenPGP key does not exist, or is invalid, or has been deleted."));

        // assertArmoredChallenge
        if (armoredChallenge == null || armoredChallenge.isEmpty()
                || !armoredChallenge.contains("-----BEGIN PGP MESSAGE-----")) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user challenge is missing or invalid.");
        }

        // Decrypt with the server private key AND verify the user signature
        // (PHP GpgJwtAuthenticator::verifyChallenge — decrypt(verify: true)).
        String clearTextChallenge;
        try {
            clearTextChallenge = gpgService.decryptVerify(armoredChallenge, gpgKey.getArmoredKey());
        } catch (GpgService.InvalidSignatureException e) {
            // PHP InvalidUserSignatureException (400)
            log.warn("JWT login: invalid challenge signature for user {}: {}", userId, e.getMessage());
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user signature could not be verified.");
        } catch (Exception e) {
            log.warn("JWT login: challenge decryption failed for user {}: {}", userId, e.getMessage());
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The challenge cannot be decrypted.");
        }

        // JSON deserialization
        JwtAuthDto.ChallengePayload challenge;
        try {
            challenge = objectMapper.readValue(clearTextChallenge, JwtAuthDto.ChallengePayload.class);
        } catch (Exception e) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The challenge is invalid. Deserialization failed.");
        }

        // assertDomain (PHP InvalidDomainException — kept as its own message,
        // it triggers admin alert emails in the reference implementation)
        assertDomain(challenge.getDomain());

        // assertVersion + verify token validation, collapsed into the same
        // message PHP uses for this failure family
        try {
            assertVersion(challenge.getVersion());
            validateVerifyToken(challenge.getVerifyTokenExpiry(), challenge.getVerifyToken(), userId);
        } catch (PassboltApiException e) {
            log.warn("JWT login: challenge validation failed for user {}: {}", userId, e.getMessage());
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The challenge is invalid. Validation failed.");
        }

        // Cleanup expired verify tokens (PHP VerifyTokenCreateService —
        // physical delete of rows older than the verify token lifetime)
        tokenRepository.deleteByTypeAndCreatedBefore(TYPE_VERIFY_TOKEN,
                LocalDateTime.now().minusHours(verifyTokenExpiryHours));

        // Persist the verify token (replay protection trace)
        AuthenticationToken verifyTokenRow = new AuthenticationToken();
        verifyTokenRow.setUserId(userId);
        verifyTokenRow.setToken(challenge.getVerifyToken());
        verifyTokenRow.setType(TYPE_VERIFY_TOKEN);
        verifyTokenRow.setActive(true);
        tokenRepository.save(verifyTokenRow);

        // Issue the RS256 access token (sub = user UUID) + rotating refresh token
        String accessToken = jwtService.generateToken(userId);
        String refreshToken = createRefreshToken(userId);

        // Build the clear-text response and encrypt it with the user public key
        JwtAuthDto.ChallengePayload response = JwtAuthDto.ChallengePayload.builder()
                .version(PROTOCOL_VERSION)
                .domain(fullBaseUrl)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .verifyToken(challenge.getVerifyToken())
                .build();

        try {
            String json = objectMapper.writeValueAsString(response);
            // Encrypt with the user key and SIGN with the server key (PHP
            // makeArmoredChallenge → gpg->encryptSign).
            String armored = gpgService.encryptSign(json, gpgKey.getArmoredKey());
            log.info("JWT login successful for user {}", user.getUsername());
            return armored;
        } catch (PassboltApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("JWT login: failed to build the response challenge", e);
            throw new PassboltApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not create the response challenge.");
        }
    }

    /**
     * POST /auth/jwt/refresh.json, payload mode (PHP
     * JwtRefreshTokenAuthenticator payload branch): both user_id and
     * refresh_token must be valid UUIDs, lookup by (token, type, user_id).
     */
    @Transactional
    public RefreshResult refresh(String userId, String refreshToken) {
        if (!isUuid(refreshToken)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The refresh token should be a valid UUID.");
        }
        if (!isUuid(userId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user ID should be a valid UUID.");
        }
        AuthenticationToken token = tokenRepository
                .findByTokenAndTypeAndUserId(refreshToken, TYPE_REFRESH_TOKEN, userId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "No active refresh token matching the request could be found."));
        return rotate(token);
    }

    /**
     * POST /auth/jwt/refresh.json, cookie mode (PHP fallback when the
     * payload carries no refresh_token): lookup by (token, type) only.
     */
    @Transactional
    public RefreshResult refreshWithCookie(String cookieRefreshToken) {
        if (!isUuid(cookieRefreshToken)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The refresh token should be a valid UUID.");
        }
        AuthenticationToken token = tokenRepository
                .findByTokenAndType(cookieRefreshToken, TYPE_REFRESH_TOKEN)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "No active refresh token matching the request could be found."));
        return rotate(token);
    }

    /**
     * POST /auth/jwt/logout.json (PHP RefreshTokenLogoutService::logout).
     *
     * @param userId       the authenticated user's id
     * @param refreshToken refresh token from body or cookie; null = revoke
     *                     all the user's refresh tokens
     */
    @Transactional
    public void logout(String userId, String refreshToken) {
        if (refreshToken == null) {
            // Revoke all sessions
            List<AuthenticationToken> tokens = tokenRepository
                    .findAllByUserIdAndTypeAndActiveTrue(userId, TYPE_REFRESH_TOKEN);
            tokens.forEach(t -> t.setActive(false));
            tokenRepository.saveAll(tokens);
            log.info("JWT logout: revoked {} refresh token(s) for user {}", tokens.size(), userId);
            return;
        }

        if (!isUuid(refreshToken)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The refresh token should be a valid UUID.");
        }

        // getActiveRefreshToken(token, userId) is scoped to the current user
        // — someone else's token is simply "not found"
        AuthenticationToken token = tokenRepository
                .findByTokenAndTypeAndUserId(refreshToken, TYPE_REFRESH_TOKEN, userId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "No active refresh token matching the request could be found."));

        validateRefreshTokenUsable(token);

        token.setActive(false);
        tokenRepository.save(token);
        log.info("JWT logout: revoked refresh token for user {}", userId);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Consume the old token and issue a new access + refresh token pair
     * (PHP RefreshTokenRenewalService::renewToken — rotation; the old row
     * is deactivated, never deleted).
     */
    private RefreshResult rotate(AuthenticationToken oldToken) {
        validateRefreshTokenUsable(oldToken);

        oldToken.setActive(false);
        tokenRepository.save(oldToken);

        String accessToken = jwtService.generateToken(oldToken.getUserId());
        String newRefreshToken = createRefreshToken(oldToken.getUserId());

        return new RefreshResult(accessToken, newRefreshToken, refreshTokenExpiryDays);
    }

    /**
     * Checks on a refresh token before use (PHP
     * throwSecurityExceptionsOnInvalidRefreshToken + the user state checks
     * of getActiveRefreshToken): attached user state, consumed, expired.
     */
    private void validateRefreshTokenUsable(AuthenticationToken token) {
        // The user must still exist, not be deleted, active, not disabled
        User user = userRepository.findById(token.getUserId())
                .filter(u -> !u.getDeleted())
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The user does not exist or has been deleted."));
        if (!user.getActive() || user.getDisabled() != null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user is deactivated.");
        }

        // Consumed (PHP ConsumedRefreshTokenAccessException)
        if (!token.getActive()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The refresh token provided was already used.");
        }

        // Expired: the table has no expiry column — created + lifetime
        // (PHP AuthenticationToken::isExpired with refresh_token.expiry)
        if (token.getCreated() != null
                && token.getCreated().isBefore(LocalDateTime.now().minusDays(refreshTokenExpiryDays))) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Expired refresh token provided.");
        }
    }

    private String createRefreshToken(String userId) {
        AuthenticationToken refreshTokenRow = new AuthenticationToken();
        refreshTokenRow.setUserId(userId);
        refreshTokenRow.setToken(UUID.randomUUID().toString());
        refreshTokenRow.setType(TYPE_REFRESH_TOKEN);
        refreshTokenRow.setActive(true);
        tokenRepository.save(refreshTokenRow);
        return refreshTokenRow.getToken();
    }

    /**
     * PHP GpgJwtAuthenticator::assertDomain — rtrim '/' on both sides.
     */
    private void assertDomain(String domain) {
        if (domain == null || !rtrimSlash(domain).equals(rtrimSlash(fullBaseUrl))) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "The domain is invalid.");
        }
    }

    /**
     * PHP GpgJwtAuthenticator::assertVersion.
     */
    private void assertVersion(String version) {
        if (!PROTOCOL_VERSION.equals(version)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "The version is invalid.");
        }
    }

    /**
     * PHP VerifyTokenValidationService::validateToken: the expiry must be
     * set, not further than now + verify token lifetime, not in the past;
     * the token must be a UUID never seen before (replay protection).
     */
    private void validateVerifyToken(Long verifyTokenExpiry, String verifyToken, String userId) {
        long now = Instant.now().getEpochSecond();
        long maxExpiry = now + verifyTokenExpiryHours * 3600L;

        if (verifyTokenExpiry == null || verifyTokenExpiry > maxExpiry) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "Invalid verify token expiry.");
        }
        if (verifyTokenExpiry < now) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Attempt to access an expired verify token.");
        }
        if (!isUuid(verifyToken)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "Invalid verify token format.");
        }
        if (tokenRepository.existsByTokenAndUserIdAndType(verifyToken, userId, TYPE_VERIFY_TOKEN)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Verify token has been already used in the past.");
        }
    }

    private static boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    private static String rtrimSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
