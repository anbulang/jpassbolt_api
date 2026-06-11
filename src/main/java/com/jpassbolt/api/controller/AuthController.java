package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.AuthDto;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Authentication controller implementing Passbolt GPG authentication protocol.
 *
 * The GPG authentication protocol uses custom X-GPGAuth-* headers to
 * communicate
 * authentication state between client and server.
 *
 * Headers used:
 * - X-GPGAuth-Authenticated: true/false
 * - X-GPGAuth-Progress: stage0/stage1/stage2/complete
 * - X-GPGAuth-User-Auth-Token: encrypted token for user to decrypt (Stage 1)
 * - X-GPGAuth-Verify-Response: decrypted server verify token (Stage 0)
 * - X-GPGAuth-Error: true if error occurred
 * - X-GPGAuth-Debug: debug information
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * GET /auth/verify.json
     * Returns the server's public key and fingerprint.
     * This endpoint is used by clients to verify the server's identity.
     */
    @GetMapping("/verify.json")
    public ResponseEntity<Map<String, Object>> verify() {
        String publicKey = authService.getServerPublicKey();
        String fingerprint = authService.getServerKeyFingerprint();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fingerprint", fingerprint);
        body.put("keydata", publicKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("header", Map.of(
                "id", java.util.UUID.randomUUID().toString(),
                "status", "success",
                "servertime", System.currentTimeMillis() / 1000,
                "action", "cd37a3ca-7d88-5fb6-bb02-f1b56423f03a",
                "message", "The operation was successful.",
                "code", 200,
                "url", "/auth/verify.json"));
        response.put("body", body);

        return ResponseEntity.ok(response);
    }

    /**
     * POST /auth/login.json
     * Handles the multi-stage GPG authentication process.
     *
     * Stage 0: Client sends server_verify_token for server to decrypt (server
     * identity verification)
     * Stage 1: Client sends keyid, server returns encrypted token (user identity
     * challenge)
     * Stage 2: Client sends user_token_result, server verifies and issues JWT
     * (authentication complete)
     */
    @PostMapping("/login.json")
    public ResponseEntity<?> login(@RequestBody AuthDto.LoginRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-GPGAuth-Authenticated", "false");

        AuthDto.GpgAuth gpgAuth = null;
        if (request.getData() != null) {
            gpgAuth = request.getData().getGpgAuth();
        }

        // Check for required data
        if (gpgAuth == null) {
            return createErrorResponse(headers, "Missing gpg_auth data");
        }

        String keyId = gpgAuth.getKeyid();
        String serverVerifyToken = gpgAuth.getServerVerifyToken();
        String userTokenResult = gpgAuth.getUserTokenResult();

        try {
            // Stage 0: Server verification (optional)
            // Client encrypts a token with server's public key, server decrypts to prove
            // identity
            if (serverVerifyToken != null && !serverVerifyToken.isEmpty()) {
                return handleStage0(headers, serverVerifyToken);
            }

            // Validate keyId is present for Stage 1 and 2
            if (keyId == null || keyId.isEmpty()) {
                return createErrorResponse(headers, "Missing key ID");
            }

            // Verify user exists for this key
            Optional<User> userOpt = authService.findUserByKeyIdentifier(keyId);
            if (userOpt.isEmpty()) {
                headers.add("X-GPGAuth-Debug", "There is no user associated with this key.");
                return createErrorResponse(headers, "User not found for key: " + keyId);
            }

            // Stage 2: Complete authentication
            // Client returns the decrypted token from Stage 1
            if (userTokenResult != null && !userTokenResult.isEmpty()) {
                return handleStage2(headers, userTokenResult, userOpt.get());
            }

            // Stage 1: User authentication challenge
            // Server generates a token, encrypts with user's public key
            return handleStage1(headers, keyId);

        } catch (Exception e) {
            log.error("Authentication error", e);
            return createErrorResponse(headers, e.getMessage());
        }
    }

    /**
     * Stage 0: Server identity verification
     */
    private ResponseEntity<?> handleStage0(HttpHeaders headers, String serverVerifyToken) {
        headers.add("X-GPGAuth-Progress", "stage0");

        String decryptedToken = authService.stage0ServerVerify(serverVerifyToken);
        if (decryptedToken != null) {
            headers.add("X-GPGAuth-Verify-Response", decryptedToken);
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(createSuccessResponse("Stage 0: Server verified"));
        } else {
            return createErrorResponse(headers, "Decryption failed");
        }
    }

    /**
     * Stage 1: User authentication challenge
     */
    private ResponseEntity<?> handleStage1(HttpHeaders headers, String keyId) {
        headers.add("X-GPGAuth-Progress", "stage1");

        String encryptedNonce = authService.loginStage1(keyId);

        // URL encode and quote the encrypted message as per Passbolt protocol
        String encodedToken = URLEncoder.encode(encryptedNonce, StandardCharsets.UTF_8)
                .replace("\\", "\\\\");

        headers.add("X-GPGAuth-User-Auth-Token", encodedToken);

        return ResponseEntity.ok()
                .headers(headers)
                .body(createSuccessResponse("Stage 1: Please decrypt the token"));
    }

    /**
     * Stage 2: Complete authentication
     */
    private ResponseEntity<?> handleStage2(HttpHeaders headers, String userTokenResult, User user) {
        String jwt = authService.loginStage2(userTokenResult);

        // Mark as authenticated
        headers.set("X-GPGAuth-Authenticated", "true");
        headers.set("X-GPGAuth-Progress", "complete");
        headers.add("X-GPGAuth-Refer", "/");

        // Also include JWT in Authorization header
        headers.add("Authorization", "Bearer " + jwt);

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("active", user.getActive());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", userInfo);

        return ResponseEntity.ok()
                .headers(headers)
                .body(createResponse("success", "You are successfully logged in.", body,
                        "d54c1605-9e69-4d63-9828-090c80c0f80e", "/auth/login.json"));
    }

    /**
     * Create an error response
     */
    private ResponseEntity<?> createErrorResponse(HttpHeaders headers, String message) {
        if (headers == null) {
            headers = new HttpHeaders();
        }
        headers.add("X-GPGAuth-Error", "true");
        return ResponseEntity.ok()
                .headers(headers)
                .body(createResponse("error", message, null, "d54c1605-9e69-4d63-9828-090c80c0f80e",
                        "/auth/login.json"));
    }

    /**
     * Create a success response body
     */
    private Map<String, Object> createSuccessResponse(String message) {
        return createResponse("success", message, new LinkedHashMap<>(), "d54c1605-9e69-4d63-9828-090c80c0f80e",
                "/auth/login.json");
    }

    /**
     * Create a response body
     */
    private Map<String, Object> createResponse(String status, String message, Object body, String action, String url) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("header", Map.of(
                "id", java.util.UUID.randomUUID().toString(),
                "status", status,
                "servertime", System.currentTimeMillis() / 1000,
                "code", 200,
                "message", message,
                "action", action != null ? action : "d54c1605-9e69-4d63-9828-090c80c0f80e",
                "url", url != null ? url : "/auth/login.json"));

        response.put("body", body != null ? body : new LinkedHashMap<>());
        return response;
    }
}
