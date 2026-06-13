package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.JwtAuthDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.JwtAuthService;
import com.jpassbolt.api.service.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JWT authentication endpoints (PHP JwtAuthentication plugin):
 * <ul>
 * <li>POST /auth/jwt/login.json — GPG challenge based login</li>
 * <li>POST /auth/jwt/refresh.json — rotating refresh token</li>
 * <li>POST /auth/jwt/logout.json — refresh token revocation</li>
 * <li>GET /auth/jwt/jwks.json (+ /.well-known/jwks.json alias) — JWKS</li>
 * <li>GET /auth/jwt/rsa.json — verification public key PEM</li>
 * </ul>
 *
 * <p>
 * No class-level @RequestMapping: the well-known alias lives outside /auth.
 * Note: with context-path /api the alias is actually exposed at
 * /api/.well-known/jwks.json — a reverse proxy must map the official root
 * path if strict compatibility is needed. The alias also requires a
 * SecurityConfig permitAll entry (see integration request); /auth/** is
 * already public.
 * </p>
 *
 * <p>
 * Authentication note: /auth/** is permitAll in SecurityConfig, so
 * /auth/jwt/logout.json enforces authentication manually (the PHP plugin
 * requires a valid access token for logout).
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class JwtAuthController {

    /** PHP RefreshTokenAbstractService::REFRESH_TOKEN_COOKIE */
    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final JwtAuthService jwtAuthService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    /**
     * POST /auth/jwt/login.json
     * Public endpoint (spec security: []). Body: {user_id, challenge}.
     * Response body: {challenge} — the armored, encrypted server challenge
     * carrying access_token + refresh_token + verify_token.
     */
    @PostMapping("/auth/jwt/login.json")
    public ResponseEntity<Map<String, Object>> login(@RequestBody JwtAuthDto.LoginRequest request) {
        String armoredResponse = jwtAuthService.login(request.getUserId(), request.getChallenge());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challenge", armoredResponse);

        return ResponseEntity.ok(createResponse("success", "The authentication was a success.", body,
                "e8f6e1a8-39c0-5d4d-9a5b-1f8a93b6b4c1", "/auth/jwt/login.json"));
    }

    /**
     * POST /auth/jwt/refresh.json
     * Authenticated by the refresh token itself (PHP
     * JwtRefreshTokenAuthenticator). Supports the payload mode
     * ({user_id, refresh_token}) and the cookie mode (refresh_token cookie).
     * The rotated refresh token travels back only via Set-Cookie; the body
     * carries the new access token.
     */
    @PostMapping("/auth/jwt/refresh.json")
    public ResponseEntity<Map<String, Object>> refresh(
            @RequestBody(required = false) JwtAuthDto.RefreshRequest request,
            @CookieValue(value = REFRESH_TOKEN_COOKIE, required = false) String cookieToken,
            HttpServletResponse response) {

        String bodyToken = request != null ? request.getRefreshToken() : null;
        String bodyUserId = request != null ? request.getUserId() : null;

        JwtAuthService.RefreshResult result;
        if (bodyToken != null || bodyUserId != null) {
            // Payload mode: both fields must be present and valid
            result = jwtAuthService.refresh(bodyUserId, bodyToken);
        } else {
            // Cookie mode fallback (PHP RefreshTokenSessionIdentificationService)
            result = jwtAuthService.refreshWithCookie(cookieToken);
        }

        // Secure + HttpOnly matches PHP createHttpOnlySecureCookie. Over
        // plain http (local dev) browsers will not send it back — the
        // payload mode (user_id + refresh_token in body) is unaffected.
        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, result.refreshToken())
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(Duration.ofDays(result.refreshTokenExpiryDays()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", result.accessToken());

        return ResponseEntity.ok(createResponse("success", "The operation was successful.", body,
                "f1d2e3a4-5b6c-5d7e-8f90-a1b2c3d4e5f6", "/auth/jwt/refresh.json"));
    }

    /**
     * POST /auth/jwt/logout.json
     * Requires a valid access token (manual check — /auth/** is permitAll).
     * Empty body = revoke all the user's refresh tokens (spec
     * revokeAllSessions example); {refresh_token} = revoke that single
     * token. Also expires the refresh_token cookie.
     */
    @PostMapping("/auth/jwt/logout.json")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestBody(required = false) JwtAuthDto.LogoutRequest request,
            @CookieValue(value = REFRESH_TOKEN_COOKIE, required = false) String cookieToken,
            HttpServletResponse response) {

        String userId = getCurrentUserId();

        // Body first, cookie fallback (PHP getRefreshTokenInRequest)
        String refreshToken = request != null ? request.getRefreshToken() : null;
        if (refreshToken == null) {
            refreshToken = cookieToken;
        }

        jwtAuthService.logout(userId, refreshToken);

        // Remove the refresh token cookie (PHP removeRefreshTokenFromCookies)
        ResponseCookie expired = ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());

        return ResponseEntity.ok(createResponse("success", "The operation was successful.", null,
                "9c6b2f4e-1a3d-5e8b-bc70-2d4f6a8e0c12", "/auth/jwt/logout.json"));
    }

    /**
     * GET /auth/jwt/jwks.json (+ /.well-known/jwks.json alias — the PHP
     * top-level routes redirect the well-known path here).
     *
     * Normalized endpoint: NO {header, body} envelope. The PHP
     * JwksController::jwks explicitly skips the regular envelope; the
     * browser extension fetches this raw RFC 7517 document to verify
     * access token signatures.
     */
    @GetMapping({ "/auth/jwt/jwks.json", "/.well-known/jwks.json" })
    public ResponseEntity<Map<String, Object>> jwks() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("keys", List.of(buildJwk()));
        return ResponseEntity.ok(response);
    }

    /**
     * GET /auth/jwt/rsa.json
     * Standard envelope with the JWT verification public key PEM. Per the
     * spec: "This is not the key to use when encrypting the JWT login
     * challenge" (that one is the GPG server public key).
     */
    @GetMapping("/auth/jwt/rsa.json")
    public ResponseEntity<Map<String, Object>> rsa() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keydata", jwtService.getPublicKeyPem());

        return ResponseEntity.ok(createResponse("success", "The operation was successful.", body,
                "5f2a1b3c-7d8e-5f60-91a2-b3c4d5e6f708", "/auth/jwt/rsa.json"));
    }

    /**
     * Build the RFC 7517 JWK for the RS256 verification key. n and e are
     * base64url WITHOUT padding over the raw magnitude bytes — the sign-bit
     * leading 0x00 of BigInteger.toByteArray() MUST be stripped, otherwise
     * clients rebuild a wrong RSA key and every signature check fails.
     */
    private Map<String, Object> buildJwk() {
        RSAPublicKey publicKey = jwtService.getRsaPublicKey();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("alg", "RS256");
        jwk.put("use", "sig");
        jwk.put("e", encoder.encodeToString(stripLeadingZero(publicKey.getPublicExponent().toByteArray())));
        jwk.put("n", encoder.encodeToString(stripLeadingZero(publicKey.getModulus().toByteArray())));
        return jwk;
    }

    private byte[] stripLeadingZero(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private Map<String, Object> createResponse(String status, String message, Object body, String action, String url) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("header", Map.of(
                "id", java.util.UUID.randomUUID().toString(),
                "status", status,
                "servertime", System.currentTimeMillis() / 1000,
                "code", 200,
                "message", message,
                "action", action != null ? action : "e8f6e1a8-39c0-5d4d-9a5b-1f8a93b6b4c1",
                "url", url != null ? url : "/auth/jwt/login.json"));

        response.put("body", body != null ? body : new LinkedHashMap<>());
        return response;
    }

    /**
     * Resolve the current user id. /auth/** is permitAll, so anonymous
     * requests reach the handler with an AnonymousAuthenticationToken —
     * treat that as unauthenticated (401), matching the PHP behaviour for
     * /auth/jwt/logout.json.
     */
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth instanceof AnonymousAuthenticationToken) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED,
                    "Authentication is required to continue.");
        }
        String username = auth.getName();
        Optional<User> user = userRepository.findByUsername(username);
        return user.map(User::getId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "User not found: " + username));
    }
}
