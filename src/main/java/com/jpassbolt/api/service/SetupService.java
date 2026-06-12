package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.SetupDto;
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

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * Account setup (activation) flow, ported from the PHP reference
 * (SetupStartController / SetupCompleteController + SetupCompleteService /
 * AbstractCompleteService / SetupStartUserInfoService).
 *
 * <p>
 * Lifecycle: an admin invites a user (POST /users.json → inactive user +
 * register token), the user opens the setup link (GET /setup/start) and
 * finally uploads their OpenPGP public key (PUT /setup/complete) which
 * activates the account and consumes the token. After completion the user
 * can run the GpgAuth three-stage login.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

    public static final String TOKEN_TYPE_REGISTER = "register";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final UserRepository userRepository;
    private final AuthenticationTokenRepository authenticationTokenRepository;
    private final GpgKeyRepository gpgKeyRepository;
    private final GpgKeyParserService gpgKeyParserService;

    /**
     * Register token lifetime in days. The authentication_tokens table has
     * no expiry column: expiry = created + N days, evaluated at consumption
     * time (PHP default PASSBOLT_AUTH_REGISTER_TOKEN_EXPIRY = '10 days').
     */
    @Value("${jpassbolt.auth.register-token-expiry-days:10}")
    private long registerTokenExpiryDays;

    /**
     * GET /setup/start/{userId}/{tokenId}.json — read-only sanity check of
     * the setup link. Returns the pending user; writes nothing.
     */
    @Transactional(readOnly = true)
    public User startSetup(String userId, String tokenId) {
        if (!isUuid(userId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user identifier should be a valid UUID.");
        }
        if (!isUuid(tokenId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The authentication token should be a valid UUID.");
        }
        User user = getAndAssertPendingUser(userId,
                "The user does not exist or is already active or is disabled.");
        getAndAssertRegisterToken(userId, tokenId);
        return user;
    }

    /**
     * PUT|POST /setup/complete/{userId}.json — validates the register token,
     * parses the uploaded public key with Bouncy Castle, then in the same
     * transaction: saves the GpgKey, activates the user, deactivates the
     * token.
     */
    @Transactional
    public User completeSetup(String userId, SetupDto.CompleteRequest request) {
        if (!isUuid(userId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user identifier should be a valid UUID.");
        }
        User user = getAndAssertPendingUser(userId,
                "The user does not exist, is already active or has been deleted.");

        String tokenValue = extractTokenValue(request);
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "An authentication token should be provided.");
        }
        if (!isUuid(tokenValue)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The authentication token should be a valid UUID.");
        }
        AuthenticationToken token = getAndAssertRegisterToken(userId, tokenValue);

        if (request.getGpgkey() == null || request.getGpgkey().getArmoredKey() == null
                || request.getGpgkey().getArmoredKey().isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "An OpenPGP key must be provided.");
        }

        GpgKeyParserService.GpgKeyMetadata metadata;
        try {
            metadata = gpgKeyParserService.parse(request.getGpgkey().getArmoredKey());
        } catch (IllegalArgumentException e) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // Fingerprint must be unique among non-deleted keys, otherwise two
        // users would share one key and GpgAuth stage 1 (lookup by
        // fingerprint) becomes ambiguous.
        if (gpgKeyRepository.findByFingerprintAndDeletedFalse(metadata.getFingerprint()).isPresent()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The OpenPGP key fingerprint is already in use.");
        }

        GpgKey gpgKey = new GpgKey();
        gpgKey.setUserId(user.getId());
        gpgKey.setArmoredKey(request.getGpgkey().getArmoredKey());
        String uid = metadata.getUid() != null ? metadata.getUid() : user.getUsername();
        gpgKey.setUid(uid.length() > 769 ? uid.substring(0, 769) : uid);
        gpgKey.setBits(metadata.getBits());
        gpgKey.setFingerprint(metadata.getFingerprint());
        gpgKey.setKeyId(metadata.getKeyId());
        gpgKey.setType(metadata.getType());
        gpgKey.setExpires(metadata.getExpires());
        gpgKey.setKeyCreated(metadata.getKeyCreated());
        gpgKey.setDeleted(false);
        gpgKeyRepository.save(gpgKey);

        user.setActive(true);
        userRepository.save(user);

        token.setActive(false);
        authenticationTokenRepository.save(token);

        log.info("Setup completed for user {} (fingerprint {})", user.getUsername(),
                metadata.getFingerprint());
        return user;
    }

    /**
     * Legacy fallback: pre-v3.6 plugins send "authenticationtoken" instead
     * of "authentication_token".
     */
    private String extractTokenValue(SetupDto.CompleteRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getAuthenticationToken() != null
                && request.getAuthenticationToken().getToken() != null) {
            return request.getAuthenticationToken().getToken();
        }
        if (request.getAuthenticationtokenLegacy() != null) {
            return request.getAuthenticationtokenLegacy().getToken();
        }
        return null;
    }

    /**
     * The user must exist, be inactive, not soft-deleted and not disabled
     * (PHP UserGetService::getNotActiveNotDeletedNotDisabledOrFail).
     */
    private User getAndAssertPendingUser(String userId, String errorMessage) {
        return userRepository.findById(userId)
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .filter(u -> !Boolean.TRUE.equals(u.getActive()))
                .filter(u -> u.getDisabled() == null)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST, errorMessage));
    }

    /**
     * The token must be active, of type register, belong to the user and be
     * younger than the configured expiry window.
     */
    private AuthenticationToken getAndAssertRegisterToken(String userId, String tokenValue) {
        AuthenticationToken token = authenticationTokenRepository
                .findByTokenAndUserIdAndTypeAndActiveTrue(tokenValue, userId, TOKEN_TYPE_REGISTER)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The authentication token is not valid."));
        if (token.getCreated() != null
                && token.getCreated().plusDays(registerTokenExpiryDays).isBefore(LocalDateTime.now())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The authentication token is not valid.");
        }
        return token;
    }

    private boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }
}
