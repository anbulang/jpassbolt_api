package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.AuthDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.service.AuthService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

        // 迁移到共享信封工具：保留显式 action 与 code=200（GpgAuth Stage 0 既有偏差），body 透传。
        Map<String, Object> response = ApiResponse.withExplicitAction("success",
                "The operation was successful.", body, 200,
                "cd37a3ca-7d88-5fb6-bb02-f1b56423f03a", "/auth/verify.json");

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
            // PHP: a malformed gpg_auth block is a 400 (AuthLoginControllerTest:192).
            return createErrorResponse(headers, HttpStatus.BAD_REQUEST, "Missing gpg_auth data");
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
                // PHP debug text for this case is "…No key id set." — 400 (:192).
                return createErrorResponse(headers, HttpStatus.BAD_REQUEST, "Missing key ID");
            }

            // Verify user exists for this key
            Optional<User> userOpt = authService.findUserByKeyIdentifier(keyId);
            if (userOpt.isEmpty()) {
                // PHP answers 400 here (:96) — deleted and disabled users take the
                // same path and are deliberately indistinguishable from "unknown
                // key", so this must not leak a different status per case.
                headers.add("X-GPGAuth-Debug", "There is no user associated with this key.");
                return createErrorResponse(headers, HttpStatus.BAD_REQUEST,
                        "There is no user associated with this key.");
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
            return createErrorResponse(headers, gpgAuthErrorStatus(e), e.getMessage());
        }
    }

    /**
     * POST /auth/verify.json
     * Server identity verification (GpgAuth Stage 0).
     *
     * The PHP routes (config/routes.php) map POST /auth/verify onto
     * AuthLogin::loginPost — the exact same Stage 0 logic as
     * /auth/login.json, so it shares that action's error statuses
     * (see createErrorResponse / gpgAuthErrorStatus) plus the
     * X-GPGAuth-Error header.
     */
    @PostMapping("/verify.json")
    public ResponseEntity<?> verifyPost(@RequestBody AuthDto.LoginRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-GPGAuth-Authenticated", "false");

        AuthDto.GpgAuth gpgAuth = null;
        if (request.getData() != null) {
            gpgAuth = request.getData().getGpgAuth();
        }
        if (gpgAuth == null) {
            return createErrorResponse(headers, HttpStatus.BAD_REQUEST, "Missing gpg_auth data");
        }

        String serverVerifyToken = gpgAuth.getServerVerifyToken();
        if (serverVerifyToken == null || serverVerifyToken.isEmpty()) {
            return createErrorResponse(headers, HttpStatus.BAD_REQUEST, "Missing server verify token");
        }

        return handleStage0(headers, serverVerifyToken);
    }

    /**
     * POST /auth/logout.json
     * PHP AuthLogoutController allows unauthenticated access and destroys
     * the server session. This API is stateless (JWT Bearer), so there is no
     * server session to destroy: the endpoint is a protocol-compatibility
     * action that always succeeds. JWT refresh token revocation lives in
     * POST /auth/jwt/logout.json. Only POST is registered — GET is disabled
     * by default in PHP (passbolt.security.getLogoutEndpointEnabled).
     */
    @PostMapping("/logout.json")
    public ResponseEntity<Map<String, Object>> logout() {
        return ResponseEntity.ok(createResponse("success", "You are successfully logged out.", null,
                "2032ed46-bbd4-5b43-a18f-152b2a48ec26", "/auth/logout.json"));
    }

    /**
     * GET /auth/is-authenticated.json
     * PHP AuthIsAuthenticatedController::isAuthenticated only asserts JSON
     * and returns success — authentication itself is enforced by the
     * framework. Here /auth/** is permitAll in SecurityConfig, so the
     * authentication state is checked manually: anonymous callers get a 401
     * error envelope (via GlobalExceptionHandler), authenticated callers a
     * success envelope. Useful as a session keep-alive probe.
     */
    @GetMapping("/is-authenticated.json")
    public ResponseEntity<Map<String, Object>> isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth instanceof AnonymousAuthenticationToken) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED,
                    "Authentication is required to continue.");
        }
        return ResponseEntity.ok(createResponse("success", "The operation was successful.", null,
                "8f8c39e5-3a23-5e69-9449-7a32b0962b04", "/auth/is-authenticated.json"));
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
            // INFERRED, not copied: the official Stage 0 wrong-key test
            // (AuthLoginControllerTest:337) asserts only the headers, never a
            // status code, so there is no authority to match here. 400 follows
            // the same responsibility rule as the rest — the caller handed us
            // ciphertext this server cannot decrypt, which is caller input, not
            // a server fault. Revisit if the official client turns out to
            // expect otherwise.
            //
            // Reviewed and kept deliberately (Codex P2, PR #5): the suggestion
            // was to answer 500 when the decrypt itself blew up, on the grounds
            // that it signals a broken server key. That distinction cannot be
            // drawn here — AuthService.stage0ServerVerify() collapses BOTH a bad
            // client ciphertext and a server-side GPG failure into `null` — and
            // splitting it would misreport ordinary bad input as 500 just as
            // often as the reverse. Official Passbolt does not distinguish them
            // either (hence the missing status assertion), and a genuinely
            // missing/broken server key fails at startup via config validation,
            // long before this path. Deliberate no-change, not an oversight.
            return createErrorResponse(headers, HttpStatus.BAD_REQUEST, "Decryption failed");
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
        // `user` is the account proven by the request's keyid — the login token
        // must belong to THEM, and the JWT is minted for THEM (see loginStage2).
        String jwt = authService.loginStage2(userTokenResult, user);

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
     * Create a GpgAuth error response.
     *
     * <p>
     * The status code matters for compatibility: official Passbolt answers
     * these with 400/500 (never 200), and a client that keys off the status —
     * including the official browser extension — misreads a 200 as success.
     * The {@code X-GPGAuth-Error} header is set either way, so header-based
     * clients keep working across both.
     * </p>
     */
    private ResponseEntity<?> createErrorResponse(HttpHeaders headers, HttpStatus status, String message) {
        if (headers == null) {
            headers = new HttpHeaders();
        }
        headers.add("X-GPGAuth-Error", "true");
        // header.code must track the transport status, not stay pinned at 200:
        // official Passbolt fills the envelope's code with the actual error code
        // (AppController::_error -> 'code' => $errorCode). Leaving it at 200 next
        // to a 400/500 response would tell envelope-reading clients "success"
        // while the transport says otherwise.
        return ResponseEntity.status(status)
                .headers(headers)
                .body(createResponse("error", message, null, "d54c1605-9e69-4d63-9828-090c80c0f80e",
                        "/auth/login.json", status.value()));
    }

    /**
     * Map a GpgAuth failure onto the HTTP status official Passbolt answers with.
     *
     * <p>
     * The official split is by RESPONSIBILITY, not by error kind: every failure
     * caused by what the CALLER sent is a 400 — unknown key
     * ({@code AuthLoginControllerTest:96}), missing key id ({@code :192}), bad
     * {@code user_token} ({@code :511}) — and only the server's own GnuPG
     * configuration being broken is a 500 ({@code :136}, {@code :149}).
     * </p>
     *
     * <p>
     * {@link AuthService} instead models these with HTTP-semantic codes (404
     * unknown user, 400 bad nonce, 401 invalid/expired token), so they are
     * folded back onto the official code here rather than surfaced as-is. Note
     * this deliberately turns the token-invalid case from 401 into 400: a 401
     * would tell clients "your session died" when in fact the login attempt
     * itself was rejected.
     * </p>
     */
    private HttpStatus gpgAuthErrorStatus(Throwable e) {
        if (e instanceof PassboltApiException pae) {
            HttpStatus status = pae.getStatus();
            // A server-side status stays as-is; everything else is caller input.
            return status.is5xxServerError() ? status : HttpStatus.BAD_REQUEST;
        }
        // GPG runtime failures / misconfiguration are the server's fault.
        return HttpStatus.INTERNAL_SERVER_ERROR;
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
    /** Success-path envelope (header.code = 200). */
    private Map<String, Object> createResponse(String status, String message, Object body, String action, String url) {
        return createResponse(status, message, body, action, url, 200);
    }

    /**
     * Envelope with an explicit {@code header.code}.
     *
     * <p>
     * Error paths MUST pass their real transport status: official Passbolt sets
     * the envelope code from the error code itself
     * ({@code AppController::_error -> 'code' => $errorCode}), so a 400/500
     * response carrying {@code code: 200} would contradict its own status line.
     * </p>
     */
    private Map<String, Object> createResponse(String status, String message, Object body, String action, String url,
            int code) {
        // 迁移到共享信封工具：保留显式 action，body null→{}。
        return ApiResponse.withExplicitAction(status, message, body != null ? body : new LinkedHashMap<>(), code,
                action != null ? action : "d54c1605-9e69-4d63-9828-090c80c0f80e",
                url != null ? url : "/auth/login.json");
    }
}
