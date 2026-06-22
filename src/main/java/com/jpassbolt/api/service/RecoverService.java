package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.RecoverDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.email.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Account-recovery flow, ported from the PHP reference
 * (UsersRecoverController + UserRecoverService, RecoverStartController +
 * RecoverStartUserInfoService, RecoverCompleteController +
 * RecoverCompleteService, RecoverAbortController + RecoverAbortService).
 *
 * <p>
 * Lifecycle: an ACTIVE user who lost their passphrase requests recovery
 * (POST /users/recover.json → a "recover" AuthenticationToken + an email with
 * the recover link). They open the link (GET /setup/recover/start), then
 * re-submit their EXISTING public key (PUT /setup/recover/complete). Unlike
 * setup, recovery installs no new key and never changes user.active: it only
 * proves the user still holds the matching key and consumes the recover token.
 * </p>
 *
 * <p>
 * Crucial CE distinction from {@link SetupService}: setup operates on a
 * NOT-active user and a "register" token and activates the account; recovery
 * operates on an ACTIVE, not-deleted, not-disabled user and a "recover" token
 * and changes no user/key state.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoverService {

    /** Free-form authentication_tokens.type value (varchar(32)) — no schema change. */
    public static final String TOKEN_TYPE_RECOVER = "recover";

    public static final String RECOVERY_CASE_DEFAULT = "default";
    public static final String RECOVERY_CASE_LOST_PASSPHRASE = "lost-passphrase";
    private static final List<String> RECOVERY_CASES =
            List.of(RECOVERY_CASE_DEFAULT, RECOVERY_CASE_LOST_PASSPHRASE);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final UserRepository userRepository;
    private final AuthenticationTokenRepository authenticationTokenRepository;
    private final GpgKeyRepository gpgKeyRepository;
    private final GpgKeyParserService gpgKeyParserService;
    private final MailService mailService;

    /**
     * Recover token lifetime in days. The authentication_tokens table has no
     * expiry column: expiry = created + N days, evaluated at consumption time
     * (PHP default PASSBOLT_AUTH_RECOVER_TOKEN_EXPIRY = '10 days'). Defaults to
     * the same 10-day window as the register token.
     */
    @Value("${jpassbolt.auth.recover-token-expiry-days:10}")
    private long recoverTokenExpiryDays;

    /**
     * POST /users/recover.json — enumeration-safe recovery request. PHP
     * UserRecoverService::recover:
     * <ul>
     *   <li>user exists + active → issue a "recover" token;</li>
     *   <li>user exists but NOT active → issue a "register" token (restart the
     *       setup the user never finished, GH passbolt/passbolt_api#73);</li>
     *   <li>user absent / soft-deleted / disabled → return success anyway
     *       (do not leak existence). The PHP NotFoundException is swallowed by
     *       the controller when passbolt.security.preventEmailEnumeration is on
     *       (the secure default).</li>
     * </ul>
     * The recover link (userId + tokenId) is logged at INFO in place of the
     * email this backend cannot send, mirroring the project's invite handling.
     *
     * @return the issued token, or null when no user was eligible (success is
     *         still returned to the caller for enumeration safety).
     */
    @Transactional
    public AuthenticationToken recover(RecoverDto.RecoverRequest request) {
        String username = request != null ? request.getUsername() : null;
        if (username == null || username.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Please provide a valid email address.");
        }
        // Validate the optional recovery case before any side effect (PHP
        // assertRecoveryCase runs inside recover()).
        assertRecoveryCase(request.getRecoveryCase());

        User user = userRepository.findByUsername(username.trim().toLowerCase())
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .filter(u -> u.getDisabled() == null)
                .orElse(null);
        if (user == null) {
            // Enumeration safety: pretend everything is fine.
            log.info("Recovery requested for unknown/ineligible username (no token issued).");
            return null;
        }

        String tokenType = Boolean.TRUE.equals(user.getActive())
                ? TOKEN_TYPE_RECOVER
                : SetupService.TOKEN_TYPE_REGISTER;

        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID().toString());
        token.setType(tokenType);
        token.setActive(true);
        authenticationTokenRepository.save(token);

        // Deliver the link by email. Active user -> recovery email; a not-yet-active
        // user (register token) -> setup invite to restart setup. MailService is
        // best-effort: when email is disabled/unconfigured it logs the link instead,
        // so this never breaks the (already-committed) token issuance.
        if (TOKEN_TYPE_RECOVER.equals(tokenType)) {
            mailService.sendRecoverEmail(user.getUsername(), user.getId(), token.getToken(),
                    request.getRecoveryCase());
        } else {
            mailService.sendSetupInviteEmail(user.getUsername(), user.getId(), token.getToken());
        }
        return token;
    }

    /**
     * GET /setup/recover/start/{userId}/{tokenId}.json — read-only validation
     * of the recovery link. Returns the active user; writes nothing.
     */
    @Transactional(readOnly = true)
    public User startRecover(String userId, String tokenId) {
        if (!isUuid(userId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user identifier should be a valid UUID.");
        }
        if (!isUuid(tokenId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The authentication token should be a valid UUID.");
        }
        User user = getAndAssertActiveUser(userId,
                "The user does not exist or is not active or is disabled.");
        getAndAssertRecoverToken(userId, tokenId);
        return user;
    }

    /**
     * PUT|POST /setup/recover/complete/{userId}.json — validates the recover
     * token, parses the submitted PUBLIC key with Bouncy Castle, and asserts
     * its fingerprint matches the user's already-stored, non-deleted key. On
     * success the recover token is consumed (active=false). NOTHING else
     * changes — no new key, no user.active flip (PHP RecoverCompleteService:
     * "Unlike setup completion we do not update anything").
     */
    @Transactional
    public User completeRecover(String userId, RecoverDto.CompleteRequest request) {
        if (!isUuid(userId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user identifier should be a valid UUID.");
        }
        User user = getAndAssertActiveUser(userId,
                "The user does not exist, has not completed the setup or was deleted.");

        String tokenValue = extractCompleteTokenValue(request);
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "An authentication token should be provided.");
        }
        if (!isUuid(tokenValue)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The authentication token should be a valid UUID.");
        }
        AuthenticationToken token = getAndAssertRecoverToken(userId, tokenValue);

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

        // CE rule: the submitted key must already belong to this user.
        if (gpgKeyRepository
                .findByFingerprintAndUserIdAndDeletedFalse(metadata.getFingerprint(), user.getId())
                .isEmpty()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The key provided does not belong to given user.");
        }

        token.setActive(false);
        authenticationTokenRepository.save(token);

        log.info("Recovery completed for user {} (fingerprint {})", user.getUsername(),
                metadata.getFingerprint());
        mailService.sendRecoverCompleteEmail(user.getUsername());
        return user;
    }

    /**
     * PUT|POST /setup/recover/abort/{userId}.json — abort an in-progress
     * recovery: same user/token preconditions as complete, then just consume
     * the recover token (PHP RecoverAbortService).
     */
    @Transactional
    public User abortRecover(String userId, RecoverDto.AbortRequest request) {
        if (!isUuid(userId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user identifier should be a valid UUID.");
        }
        User user = getAndAssertActiveUser(userId,
                "The user does not exist, has not completed the setup or was deleted.");

        String tokenValue = extractAbortTokenValue(request);
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "An authentication token should be provided.");
        }
        if (!isUuid(tokenValue)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The authentication token should be a valid UUID.");
        }
        AuthenticationToken token = getAndAssertRecoverToken(userId, tokenValue);

        token.setActive(false);
        authenticationTokenRepository.save(token);

        log.info("Recovery aborted for user {}", user.getUsername());
        return user;
    }

    /**
     * Optional recovery case: null → "default"; otherwise must be one of
     * ACCOUNT_RECOVERY_CASES (PHP assertRecoveryCase).
     */
    private void assertRecoveryCase(String recoveryCase) {
        if (recoveryCase == null) {
            return;
        }
        if (!RECOVERY_CASES.contains(recoveryCase)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Account recovery reason not supported.");
        }
    }

    /** Legacy fallback: pre-v3.6 plugins send "authenticationtoken". */
    private String extractCompleteTokenValue(RecoverDto.CompleteRequest request) {
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

    /** Legacy fallback: pre-v3.6 plugins send "authenticationtoken". */
    private String extractAbortTokenValue(RecoverDto.AbortRequest request) {
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
     * Recovery requires the OPPOSITE of setup: the user must exist, be ACTIVE,
     * not soft-deleted and not disabled (PHP
     * UserGetService::getActiveNotDeletedNotDisabledOrFail).
     */
    private User getAndAssertActiveUser(String userId, String errorMessage) {
        return userRepository.findById(userId)
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .filter(u -> u.getDisabled() == null)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST, errorMessage));
    }

    /**
     * The token must be active, of type recover, belong to the user and be
     * younger than the configured expiry window.
     */
    private AuthenticationToken getAndAssertRecoverToken(String userId, String tokenValue) {
        AuthenticationToken token = authenticationTokenRepository
                .findByTokenAndUserIdAndTypeAndActiveTrue(tokenValue, userId, TOKEN_TYPE_RECOVER)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The authentication token is not valid."));
        if (token.getCreated() != null
                && token.getCreated().plusDays(recoverTokenExpiryDays).isBefore(LocalDateTime.now())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The authentication token is not valid.");
        }
        return token;
    }

    private boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }
}
