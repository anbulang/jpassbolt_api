package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Authentication service implementing Passbolt GPG authentication flow.
 *
 * The GPG authentication has 3 stages:
 * - Stage 0: Server verification (client verifies server identity)
 * - Stage 1: Server encrypts a token for the user to decrypt (server verifies
 * user identity)
 * - Stage 2: User returns the decrypted token to complete authentication
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String GPGAUTH_VERSION = "gpgauthv1.3.0";
    private static final int UUID_LENGTH = 36;
    private static final Pattern NONCE_PATTERN = Pattern.compile(
            "^gpgauthv1\\.3\\.0\\|36\\|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\|gpgauthv1\\.3\\.0$",
            Pattern.CASE_INSENSITIVE);

    private final GpgService gpgService;
    private final UserService userService;
    private final GpgKeyRepository gpgKeyRepository;
    private final AuthenticationTokenRepository tokenRepository;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    /**
     * Get the server's public GPG key.
     *
     * @return the armored public key string
     */
    @Transactional(readOnly = true)
    public String getServerPublicKey() {
        return gpgService.getServerPublicKey();
    }

    /**
     * Get the server's GPG key fingerprint.
     *
     * @return the fingerprint string
     */
    @Transactional(readOnly = true)
    public String getServerKeyFingerprint() {
        return gpgService.getServerKeyFingerprint();
    }

    /**
     * Stage 0: Server verification.
     * Client sends an encrypted token for the server to decrypt and return.
     * This allows the client to verify the server's identity.
     *
     * @param encryptedServerVerifyToken The encrypted token from client
     * @return The decrypted token (nonce) if valid, null otherwise
     */
    public String stage0ServerVerify(String encryptedServerVerifyToken) {
        try {
            String decryptedToken = gpgService.decrypt(encryptedServerVerifyToken);
            if (isValidNonce(decryptedToken)) {
                return decryptedToken;
            }
            log.warn("Invalid nonce format in server verify token");
            return null;
        } catch (Exception e) {
            log.error("Failed to decrypt server verify token", e);
            return null;
        }
    }

    /**
     * Stage 1: User authentication initiation.
     * Server generates a token, encrypts it with the user's public key,
     * and returns it for the user to decrypt.
     *
     * @param keyId The fingerprint or key ID used to identify the user
     * @return The encrypted token for the user to decrypt
     */
    @Transactional
    public String loginStage1(String keyId) {
        // Find the GPG key by fingerprint or key_id
        GpgKey gpgKey = findGpgKeyByIdentifier(keyId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND, "GPG key not found for: " + keyId));

        // Verify the user is active and not deleted
        User user = userRepository.findById(gpgKey.getUserId())
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND, "User not found for GPG key"));

        if (!user.getActive() || user.getDeleted()) {
            throw new PassboltApiException(HttpStatus.FORBIDDEN, "User is not active or has been deleted");
        }

        if (user.getDisabled() != null) {
            throw new PassboltApiException(HttpStatus.FORBIDDEN, "User account is disabled");
        }

        // Generate authentication token in Passbolt format
        String uuid = UUID.randomUUID().toString();
        String nonce = formatNonce(uuid);

        // Store the token
        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(gpgKey.getUserId());
        token.setToken(uuid); // Store only the UUID part
        token.setType("login");
        token.setActive(true);
        tokenRepository.save(token);

        // Encrypt the nonce with the user's public key
        String encryptedNonce = gpgService.encrypt(nonce, gpgKey.getArmoredKey());

        log.debug("Stage 1: Generated encrypted nonce for user {}", user.getUsername());
        return encryptedNonce;
    }

    /**
     * Stage 2: Complete authentication.
     * User sends back the decrypted nonce to prove their identity.
     *
     * @param userTokenResult The decrypted nonce from the user
     * @return JWT token on successful authentication
     */
    @Transactional
    public String loginStage2(String userTokenResult) {
        // The userTokenResult should be the decrypted nonce in format:
        // gpgauthv1.3.0|36|{UUID}|gpgauthv1.3.0
        String decryptedNonce;
        try {
            // If it's encrypted, decrypt it first
            decryptedNonce = gpgService.decrypt(userTokenResult);
        } catch (Exception e) {
            // If decryption fails, assume it's already plain text
            decryptedNonce = userTokenResult;
        }

        // Validate nonce format
        if (!isValidNonce(decryptedNonce)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "Invalid nonce format: " + decryptedNonce);
        }

        // Extract UUID from nonce
        String uuid = extractUuidFromNonce(decryptedNonce);
        if (uuid == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "Could not extract UUID from nonce");
        }

        // Find and validate the token
        AuthenticationToken token = tokenRepository.findByToken(uuid)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.UNAUTHORIZED, "Token not found: " + uuid));

        if (!token.getActive()) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "Token is no longer active");
        }

        // Get the user
        User user = userService.getUserById(token.getUserId());

        // Invalidate the token
        token.setActive(false);
        tokenRepository.save(token);

        log.info("Stage 2: User {} authenticated successfully", user.getUsername());

        // Generate JWT (RS256, sub = user UUID — aligned with the PHP JWT plugin)
        return jwtService.generateToken(user.getId());
    }

    /**
     * Find user by GPG key fingerprint (40 chars) or key_id (up to 16 chars).
     *
     * @param keyId the fingerprint or key_id
     * @return an Optional containing the user if found
     */
    @Transactional(readOnly = true)
    public Optional<User> findUserByKeyIdentifier(String keyId) {
        return findGpgKeyByIdentifier(keyId)
                .flatMap(gpgKey -> userRepository.findById(gpgKey.getUserId()));
    }

    /**
     * Find GPG key by fingerprint or key_id.
     */
    private Optional<GpgKey> findGpgKeyByIdentifier(String keyId) {
        String normalizedKeyId = keyId.toUpperCase();

        // If it's a 40-character string, treat as fingerprint
        if (normalizedKeyId.length() == 40) {
            return gpgKeyRepository.findByFingerprintAndDeletedFalse(normalizedKeyId);
        }

        // Otherwise, try key_id lookup
        return gpgKeyRepository.findByKeyIdAndDeletedFalse(normalizedKeyId);
    }

    /**
     * Format a UUID into the Passbolt nonce format.
     */
    public static String formatNonce(String uuid) {
        return GPGAUTH_VERSION + "|" + UUID_LENGTH + "|" + uuid + "|" + GPGAUTH_VERSION;
    }

    /**
     * Validate that a nonce matches the expected Passbolt format.
     */
    public static boolean isValidNonce(String nonce) {
        if (nonce == null || nonce.isEmpty()) {
            return false;
        }
        return NONCE_PATTERN.matcher(nonce).matches();
    }

    /**
     * Extract the UUID from a Passbolt nonce.
     */
    public static String extractUuidFromNonce(String nonce) {
        if (!isValidNonce(nonce)) {
            return null;
        }
        String[] parts = nonce.split("\\|");
        if (parts.length != 4) {
            return null;
        }
        return parts[2];
    }
}
