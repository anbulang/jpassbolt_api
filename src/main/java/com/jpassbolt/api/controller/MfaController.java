package com.jpassbolt.api.controller;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.dto.MfaDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.MfaService;
import com.jpassbolt.api.service.TotpService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MFA endpoints (port of the PHP MultiFactorAuthentication plugin
 * controllers):
 *
 * <ul>
 * <li>GET/POST /mfa/verify/{mfaProviderName}.json — TotpVerifyGetController /
 * TotpVerifyPostController (OpenAPI domain)</li>
 * <li>GET|POST|PUT|DELETE /mfa/verify/error.json —
 * MfaVerifyAjaxErrorController (OpenAPI domain, four methods like the PHP
 * routes)</li>
 * <li>GET/POST/DELETE /mfa/setup/totp.json + GET /mfa/setup/totp/start.json —
 * TotpSetupGet/Post/DeleteController (outside the OpenAPI domain)</li>
 * <li>GET/POST|PUT /mfa/settings.json — MfaOrgSettingsGet/PostController,
 * admins only (outside the OpenAPI domain)</li>
 * </ul>
 *
 * <p>
 * Route note: the "/verify/error.json" literal mapping coexists with the
 * "/verify/{mfaProviderName}.json" template — Spring's PathPattern matching
 * always prefers the literal, so error.json never falls into the template.
 * </p>
 *
 * <p>
 * Known functional downgrade vs PHP (blueprint risk, recorded for the daily
 * log): no QR code rendering — adding a QR encoder would need a new
 * dependency (forbidden) and hand-writing one is unrealistic, so
 * {@code otpQrCodeSvg} is always an empty string. Clients must render the
 * QR locally from {@code otpProvisioningUri} (the React frontend can) or
 * type the secret manually.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/mfa")
@RequiredArgsConstructor
public class MfaController {

    /** PHP MfaVerifiedCookie::MFA_COOKIE_ALIAS. */
    public static final String MFA_COOKIE = "passbolt_mfa";

    private final MfaService mfaService;
    private final TotpService totpService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final SettingsProperties settingsProperties;

    // ------------------------------------------------------------------
    // Verify (OpenAPI domain)
    // ------------------------------------------------------------------

    /**
     * GET /mfa/verify/{mfaProviderName}.json — check whether MFA
     * verification is pending for this provider. 400 means "not required /
     * not available", 200 means "please provide the one-time password".
     */
    @GetMapping({ "/verify/{mfaProviderName}", "/verify/{mfaProviderName}.json" })
    public ResponseEntity<Map<String, Object>> verifyCheck(
            @PathVariable String mfaProviderName,
            @CookieValue(value = MFA_COOKIE, required = false) String mfaCookie) {
        String url = "/mfa/verify/" + mfaProviderName + ".json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> guard = verifyPreconditions(mfaProviderName, mfaCookie, userId, url);
        if (guard != null) {
            return guard;
        }
        return ResponseEntity.ok(createNullBodyResponse("success",
                "Please provide the one-time password.", url));
    }

    /**
     * POST /mfa/verify/{mfaProviderName}.json — attempt MFA verification
     * with a one-time password. On success a passbolt_mfa cookie carrying
     * the new authentication_tokens(type='mfa') row UUID is set.
     */
    @PostMapping({ "/verify/{mfaProviderName}", "/verify/{mfaProviderName}.json" })
    public ResponseEntity<Map<String, Object>> verifyAttempt(
            @PathVariable String mfaProviderName,
            @RequestBody(required = false) MfaDto.VerifyRequest request,
            @CookieValue(value = MFA_COOKIE, required = false) String mfaCookie,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        String url = "/mfa/verify/" + mfaProviderName + ".json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> guard = verifyPreconditions(mfaProviderName, mfaCookie, userId, url);
        if (guard != null) {
            return guard;
        }

        // Brute-force protection: 429 while locked out, count every failure.
        mfaService.assertMfaAttemptAllowed(userId);
        Map<String, String> errors = mfaService.validateTotpCode(userId,
                request != null ? request.getTotp() : null);
        mfaService.recordMfaAttempt(userId, errors.isEmpty());
        if (!errors.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("totp", errors);
            return ResponseEntity.status(400).body(createResponse("error",
                    "Something went wrong when validating the one-time password.", body, url));
        }

        // OpenAPI mfaAttempt: remember is an integer enum 0|1
        boolean remember = request != null && request.getRemember() != null && request.getRemember() != 0;
        String token = mfaService.createMfaVerifiedToken(userId, MfaService.PROVIDER_TOTP, remember, userAgent);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildMfaCookie(token, remember).toString())
                .body(createNullBodyResponse("success",
                        "The multi-factor authentication was a success.", url));
    }

    /**
     * GET|POST|PUT|DELETE /mfa/verify/error.json — the landing point of the
     * 302 issued by {@code MfaEnforcementFilter}. Always 403 with the list
     * of providers the user can verify with; also clears any stale
     * passbolt_mfa cookie (PHP _invalidateMfaCookie). Registered for the
     * four methods like the PHP routes (OpenAPI only lists GET).
     */
    @RequestMapping(value = { "/verify/error", "/verify/error.json" }, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE })
    public ResponseEntity<Map<String, Object>> verifyError() {
        String url = "/mfa/verify/error.json";
        String userId = getCurrentUserId();

        List<String> providers = mfaService.getEnabledProviders(userId);
        // "providers" (verify URL map) is a deprecated PHP field; the schema
        // only requires mfa_providers — extra keys are legal.
        Map<String, Object> providerUrls = new LinkedHashMap<>();
        for (String provider : providers) {
            providerUrls.put(provider,
                    settingsProperties.getFullBaseUrl() + "/mfa/verify/" + provider + ".json");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mfa_providers", providers);
        body.put("providers", providerUrls);

        ResponseCookie expired = ResponseCookie.from(MFA_COOKIE, "")
                .path("/").httpOnly(true).maxAge(0).build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(createErrorResponseWithCode(403,
                        "MFA authentication is required.", body, url));
    }

    // ------------------------------------------------------------------
    // TOTP setup (outside the OpenAPI domain)
    // ------------------------------------------------------------------

    /**
     * GET /mfa/setup/totp.json — when not configured yet, return a freshly
     * generated provisioning uri (never persisted; the client posts it
     * back); when already configured, return the verified timestamp.
     */
    @GetMapping({ "/setup/totp", "/setup/totp.json" })
    public ResponseEntity<Map<String, Object>> setupTotpGet() {
        String url = "/mfa/setup/totp.json";
        User user = getCurrentUser();

        ResponseEntity<Map<String, Object>> guard = orgTotpEnabledGuard(url);
        if (guard != null) {
            return guard;
        }

        if (mfaService.isTotpProviderReady(user.getId())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("verified", mfaService.getTotpVerifiedTime(user.getId()).orElse(null));
            return ResponseEntity.ok(createResponse("success",
                    "Multi Factor Authentication is configured!", body, url));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("otpProvisioningUri", mfaService.generateTotpSetupUri(user.getUsername()));
        // QR rendering is delegated to the client (see class Javadoc).
        body.put("otpQrCodeSvg", "");
        return ResponseEntity.ok(createResponse("success",
                "Please setup the TOTP application.", body, url));
    }

    /**
     * GET /mfa/setup/totp/start.json — JSON variant of the PHP start route:
     * only reports whether a setup is needed, without generating a secret.
     */
    @GetMapping({ "/setup/totp/start", "/setup/totp/start.json" })
    public ResponseEntity<Map<String, Object>> setupTotpStart() {
        String url = "/mfa/setup/totp/start.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> guard = orgTotpEnabledGuard(url);
        if (guard != null) {
            return guard;
        }

        if (mfaService.isTotpProviderReady(userId)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("verified", mfaService.getTotpVerifiedTime(userId).orElse(null));
            return ResponseEntity.ok(createResponse("success",
                    "Multi Factor Authentication is configured!", body, url));
        }
        return ResponseEntity.ok(createNullBodyResponse("success",
                "Please setup the TOTP application.", url));
    }

    /**
     * POST /mfa/setup/totp.json — enable TOTP: validate the posted
     * provisioning uri and a currently valid code, persist the account
     * settings, and mark this session as MFA-verified (session cookie, no
     * Max-Age).
     */
    @PostMapping({ "/setup/totp", "/setup/totp.json" })
    public ResponseEntity<Map<String, Object>> setupTotpPost(
            @RequestBody(required = false) MfaDto.TotpSetupRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        String url = "/mfa/setup/totp.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> guard = orgTotpEnabledGuard(url);
        if (guard != null) {
            return guard;
        }
        if (mfaService.isTotpProviderReady(userId)) {
            return ResponseEntity.status(400).body(createNullBodyResponse("error",
                    "This authentication provider is already setup. Disable it first", url));
        }

        String provisioningUri = request != null ? request.getOtpProvisioningUri() : null;
        try {
            if (provisioningUri == null) {
                throw new IllegalArgumentException("The provisioning uri is required.");
            }
            totpService.parseProvisioningUri(provisioningUri);
        } catch (IllegalArgumentException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("otpProvisioningUri",
                    Map.of("isValidOtpProvisioningUri", "This OTP provision uri is not valid."));
            return ResponseEntity.status(400).body(createResponse("error",
                    "Something went wrong when validating the one-time password.", body, url));
        }

        // Brute-force protection: the setup endpoint also validates a 6-digit
        // code and must not be usable as an oracle.
        mfaService.assertMfaAttemptAllowed(userId);
        Map<String, String> errors = mfaService.validateTotpCodeAgainstUri(provisioningUri,
                request.getTotp());
        mfaService.recordMfaAttempt(userId, errors.isEmpty());
        if (!errors.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("totp", errors);
            return ResponseEntity.status(400).body(createResponse("error",
                    "Something went wrong when validating the one-time password.", body, url));
        }

        LocalDateTime verified = mfaService.enableTotpProvider(userId, provisioningUri);
        String token = mfaService.createMfaVerifiedToken(userId, MfaService.PROVIDER_TOTP, false, userAgent);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verified", verified);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildMfaCookie(token, false).toString())
                .body(createResponse("success",
                        "Multi Factor Authentication is configured!", body, url));
    }

    /**
     * DELETE /mfa/setup/totp.json — disable TOTP (idempotent). Deleting the
     * last provider physically removes the account_settings row and
     * deactivates all mfa tokens of the user.
     */
    @DeleteMapping({ "/setup/totp", "/setup/totp.json" })
    public ResponseEntity<Map<String, Object>> setupTotpDelete() {
        String url = "/mfa/setup/totp.json";
        String userId = getCurrentUserId();

        boolean existed = mfaService.disableTotpProvider(userId);
        String message = existed
                ? "The configuration was deleted."
                : "No configuration found for this provider. Nothing to delete.";
        return ResponseEntity.ok(createNullBodyResponse("success", message, url));
    }

    // ------------------------------------------------------------------
    // Organization settings (admins only, outside the OpenAPI domain)
    // ------------------------------------------------------------------

    /**
     * GET /mfa/settings.json — the organization-level MFA configuration
     * (defaults to {"providers": []}). Admins only (PHP assertIsAdmin).
     * Historical values may contain providers JPassbolt does not support
     * (yubikey/duo) — they are echoed back as-is but can never become
     * "ready" for any user.
     */
    @GetMapping({ "/settings", "/settings.json" })
    public ResponseEntity<Map<String, Object>> getOrgSettings() {
        String url = "/mfa/settings.json";
        String userId = getCurrentUserId();
        if (!userService.isAdmin(userId)) {
            return ResponseEntity.status(403).body(createResponse("error",
                    "Access restricted to administrators.", null, url));
        }
        return ResponseEntity.ok(createResponse("success",
                "The operation was successful.", mfaService.getOrgMfaConfig(), url));
    }

    /**
     * POST|PUT /mfa/settings.json — update the organization-level MFA
     * configuration (the PHP route accepts both methods). Deliberate
     * narrowing vs PHP: only 'totp' is accepted (PHP also validates
     * duo/yubikey credentials, which JPassbolt does not implement). An empty
     * providers array disables MFA for the whole organization.
     */
    @RequestMapping(value = { "/settings", "/settings.json" }, method = {
            RequestMethod.POST, RequestMethod.PUT })
    public ResponseEntity<Map<String, Object>> setOrgSettings(
            @RequestBody(required = false) MfaDto.OrgSettingsRequest request) {
        String url = "/mfa/settings.json";
        String userId = getCurrentUserId();
        if (!userService.isAdmin(userId)) {
            return ResponseEntity.status(403).body(createResponse("error",
                    "Access restricted to administrators.", null, url));
        }

        List<String> providers = request != null ? request.getProviders() : null;
        if (providers == null) {
            return ResponseEntity.status(400).body(createResponse("error",
                    "A list of providers is required.", null, url));
        }
        for (String provider : providers) {
            if (!MfaService.PROVIDER_TOTP.equals(provider)) {
                return ResponseEntity.status(400).body(createResponse("error",
                        "This authentication provider is not supported.", null, url));
            }
        }

        Map<String, Object> config = mfaService.setOrgMfaConfig(providers, userId);
        return ResponseEntity.ok(createResponse("success",
                "The multi factor authentication settings for the organization were updated.",
                config, url));
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /**
     * Shared verify preconditions (PHP MfaVerifyController::_handleVerify
     * pre-checks), evaluated in order:
     * <ol>
     * <li>a still-valid passbolt_mfa cookie → MFA is not required;</li>
     * <li>no account_settings mfa row at all → no valid settings;</li>
     * <li>the requested provider (totp|yubikey|anything) is not in the
     * org ∩ user enabled set → no valid settings for this provider.</li>
     * </ol>
     *
     * @return a 400 response, or null when verification may proceed
     */
    private ResponseEntity<Map<String, Object>> verifyPreconditions(
            String provider, String mfaCookie, String userId, String url) {
        if (mfaCookie != null && mfaService.isMfaTokenValid(userId, mfaCookie)) {
            return ResponseEntity.status(400).body(createNullBodyResponse("error",
                    "The multi-factor authentication is not required.", url));
        }
        if (!mfaService.hasAccountMfaSettings(userId)) {
            return ResponseEntity.status(400).body(createNullBodyResponse("error",
                    "No valid multi-factor authentication settings found.", url));
        }
        if (!mfaService.getEnabledProviders(userId).contains(provider)) {
            return ResponseEntity.status(400).body(createNullBodyResponse("error",
                    "No valid multi-factor authentication settings found for this provider.", url));
        }
        return null;
    }

    /**
     * 400 when the organization has not enabled totp
     * (PHP MfaSettings::getOrgSettings + assertProviderEnabled).
     */
    private ResponseEntity<Map<String, Object>> orgTotpEnabledGuard(String url) {
        if (!mfaService.getOrgEnabledProviders().contains(MfaService.PROVIDER_TOTP)) {
            return ResponseEntity.status(400).body(createNullBodyResponse("error",
                    "This authentication provider is not enabled for your organization.", url));
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Cookie helper
    // ------------------------------------------------------------------

    /**
     * passbolt_mfa cookie: Path=/, HttpOnly; with remember a 30-day Max-Age
     * (PHP DefaultRememberAMonthSettingService is always enabled in CE, so
     * no configuration switch), otherwise session-scoped.
     */
    private ResponseCookie buildMfaCookie(String token, boolean remember) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(MFA_COOKIE, token)
                .path("/")
                .httpOnly(true);
        if (remember) {
            builder.maxAge(Duration.ofDays(MfaService.MFA_TOKEN_MAX_DURATION_DAYS));
        }
        return builder.build();
    }

    // ------------------------------------------------------------------
    // Shared helpers (same pattern as ResourceController / ShareController)
    // ------------------------------------------------------------------

    /**
     * Get the current authenticated user (entity — the setup flow also
     * needs the username as the provisioning uri label).
     */
    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }

        String username = auth.getName();
        Optional<User> user = userRepository.findByUsername(username);
        return user.orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                "User not found: " + username));
    }

    /**
     * Get the current authenticated user's ID.
     */
    private String getCurrentUserId() {
        return getCurrentUser().getId();
    }

    /**
     * Create a Passbolt-style response body.
     */
    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留原 200/400 code 与 null→{} 语义。
        return ApiResponse.withCode(status, message, body, "success".equals(status) ? 200 : 400, url);
    }

    /**
     * nullBody envelope: body must be JSON null (OpenAPI responses/nullBody
     * is type 'null'; PHP success() with no data serializes "body": null) —
     * deliberate local deviation from createResponse's empty {} fallback, do
     * not generalize back to the empty map (same precedent as
     * ShareController/UsersController).
     */
    private Map<String, Object> createNullBodyResponse(String status, String message, String url) {
        // 迁移到共享信封工具：补 action(uuid)，保留 body=null 的有意偏差。
        return ApiResponse.nullBody(status, message, url);
    }

    /**
     * Error envelope with an explicit header.code — the OpenAPI example for
     * /mfa/verify/error.json requires header.code 403, unlike the project
     * convention where createResponse always emits 400 for errors
     * (deliberate local deviation, scoped to this endpoint).
     */
    private Map<String, Object> createErrorResponseWithCode(int code, String message, Object body, String url) {
        // 迁移到共享信封工具：补 action(uuid)，显式传入 code（如 403）并透传 body，保留此端点的有意偏差。
        return ApiResponse.withExplicitAction("error", message, body, code, ApiResponse.actionFor(url), url);
    }
}
