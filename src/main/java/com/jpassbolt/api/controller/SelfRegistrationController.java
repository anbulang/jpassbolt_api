package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.SelfRegistrationDto;
import com.jpassbolt.api.dto.UserDto;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.service.SelfRegistrationService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public (guest) self-registration endpoints — port of the PHP CE plugin
 * {@code Passbolt\SelfRegistration} dry-run controller + the gated core
 * {@code UsersRegisterController}.
 *
 * <ul>
 *   <li>{@code POST /self-registration/dry-run.json} — pre-check whether a guest
 *       with a given email may register. Creates nothing; 200 when allowed, else
 *       a 4xx carrying the reason.</li>
 *   <li>{@code GET /users/register.json} — is self-registration open? 200 when an
 *       admin has configured a provider, else 404.</li>
 *   <li>{@code POST /users/register.json} — the actual guest sign-up: re-run the
 *       gate, then create the (inactive, USER-role) account + register token,
 *       which emails the setup link and notifies the admins.</li>
 * </ul>
 *
 * <p>All endpoints are GUEST-only: an authenticated caller gets 403 (PHP
 * {@code assertIsGuest} / {@code role !== GUEST}). They are whitelisted in
 * {@link com.jpassbolt.api.config.SecurityConfig} (the admin settings endpoints
 * stay authenticated). The gate failures (disabled / domain / duplicate) are
 * raised as {@link com.jpassbolt.api.exception.PassboltApiException} with faithful
 * status codes and rendered by the global handler.</p>
 *
 * <p>No class-level {@code @RequestMapping}: the endpoints span two prefixes
 * ({@code /self-registration} and {@code /users}) and Boot 3's PathPatternParser
 * would mangle a class-prefix + {@code .json} suffix — full method paths avoid
 * that, mirroring {@link UsersController} / {@link RecoverController}.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SelfRegistrationController {

    private final SelfRegistrationService selfRegistrationService;
    private final UserService userService;

    /**
     * POST /self-registration/dry-run.json — validate that a guest with this
     * email may self-register, without creating anything. Guest-only.
     */
    @PostMapping({ "/self-registration/dry-run", "/self-registration/dry-run.json" })
    public ResponseEntity<Map<String, Object>> dryRun(
            @RequestBody(required = false) SelfRegistrationDto.DryRunRequest request) {
        String url = "/self-registration/dry-run.json";

        if (isAuthenticated()) {
            return guestOnly(url);
        }

        try {
            selfRegistrationService.canGuestSelfRegister(request == null ? null : request.getEmail());
        } catch (SelfRegistrationService.SelfRegistrationValidationException e) {
            return ResponseEntity.status(400).body(ApiResponse.error(e.getMessage(), e.getErrors(), url));
        }
        return ResponseEntity.ok(ApiResponse.nullBody("success",
                "The operation was successful.", url));
    }

    /**
     * GET /users/register.json — is self-registration open? Guest-only; 200 when
     * a provider is configured, 404 otherwise (PHP
     * {@code UsersRegisterController::registerGet} assertIsSelfRegistrationOpen).
     */
    @GetMapping({ "/users/register", "/users/register.json" })
    public ResponseEntity<Map<String, Object>> registerGet() {
        String url = "/users/register.json";

        if (isAuthenticated()) {
            return guestOnly(url);
        }
        if (!selfRegistrationService.isOpen()) {
            return ResponseEntity.status(404).body(ApiResponse.withCode("error",
                    "Registration is not open.", null, 404, url));
        }
        return ResponseEntity.ok(ApiResponse.nullBody("success",
                "The operation was successful.", url));
    }

    /**
     * POST /users/register.json — the actual guest sign-up. Guest-only. Re-runs
     * the full gate, then creates the account via
     * {@code UserService.createUser(request, null, true)} (forces the USER role,
     * issues a register token, and publishes the self-registration event that
     * mails the setup link + notifies the admins).
     */
    @PostMapping({ "/users/register", "/users/register.json" })
    public ResponseEntity<Map<String, Object>> registerPost(
            @RequestBody(required = false) UserDto.CreateRequest request) {
        String url = "/users/register.json";

        if (isAuthenticated()) {
            return guestOnly(url);
        }

        // Gate first (PHP registerPost re-runs canGuestSelfRegister): self-registration
        // open + email domain allow-listed + not already registered. The disabled (403),
        // duplicate (403) and bad-email (400) failures are PassboltApiException and are
        // rendered by the global handler; the domain-not-allowed case carries a
        // field-error body (PHP CustomValidationException, 400) and is rendered here.
        try {
            selfRegistrationService.canGuestSelfRegister(request == null ? null : request.getUsername());
        } catch (SelfRegistrationService.SelfRegistrationValidationException e) {
            return ResponseEntity.status(400).body(ApiResponse.error(e.getMessage(), e.getErrors(), url));
        }

        try {
            User created = userService.createUser(request, null, true);
            return ResponseEntity.ok(ApiResponse.success(
                    "The operation was successful.", buildRegisteredUserBody(created, request), url));
        } catch (UserService.UserValidationException e) {
            return ResponseEntity.status(400).body(ApiResponse.error(e.getMessage(), e.getErrors(), url));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiResponse.error(e.getMessage(), null, url));
        }
    }

    /**
     * Minimal created-user body (PHP register returns the user). The user is
     * always freshly created here — inactive, USER role — so a small projection
     * (id / username / active + profile names from the request) is sufficient for
     * the client to proceed to setup, without duplicating UsersController's full
     * userIndexAndView renderer.
     */
    private Map<String, Object> buildRegisteredUserBody(User user, UserDto.CreateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("username", user.getUsername());
        body.put("active", user.getActive());
        body.put("deleted", user.getDeleted());
        body.put("created", user.getCreated());
        body.put("modified", user.getModified());
        Map<String, Object> profile = new LinkedHashMap<>();
        UserDto.ProfilePayload payload = request == null ? null : request.getProfile();
        profile.put("first_name", payload == null ? null : payload.getFirstName());
        profile.put("last_name", payload == null ? null : payload.getLastName());
        body.put("profile", profile);
        return body;
    }

    /** 403 guest-only response with header.code matching the HTTP status (not the passthrough default 400). */
    private ResponseEntity<Map<String, Object>> guestOnly(String url) {
        return ResponseEntity.status(403).body(ApiResponse.withCode("error",
                "Only guests are allowed to self register.", null, 403, url));
    }

    /** True when a real principal is present (anonymous does not count). */
    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }
}
