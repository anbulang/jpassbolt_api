package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.UserDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service for user-related operations (create / update, ported from PHP
 * UsersTable::register + UsersEditController/editEntity).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    public static final String TOKEN_TYPE_REGISTER = "register";

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ProfileRepository profileRepository;
    private final AuthenticationTokenRepository authenticationTokenRepository;

    /**
     * Get a user by their ID.
     *
     * @param id the user ID
     * @return the user entity
     * @throws PassboltApiException if the user is not found
     */
    @Transactional(readOnly = true)
    public User getUserById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /**
     * True when the user's role is admin (resolved through the roles table,
     * never by hardcoded UUID).
     */
    @Transactional(readOnly = true)
    public boolean isAdmin(String userId) {
        return userRepository.findById(userId)
                .map(User::getRoleId)
                .flatMap(roleRepository::findById)
                .map(role -> Role.ADMIN.equals(role.getName()))
                .orElse(false);
    }

    /**
     * Admin invite-style user creation (PHP UsersTable::register):
     * username lowercased (PHP beforeMarshal mb_strtolower), uniqueness only
     * among deleted=false users, role must be admin or user (defaults to
     * user), user saved inactive with its Profile, and a register
     * AuthenticationToken is generated in the same transaction. Token expiry
     * = created + configured days, evaluated at consumption time
     * (SetupService).
     *
     * @return the saved (inactive) user
     * @throws UserValidationException on any validation failure (400, body
     *                                 carries the field error map)
     */
    @Transactional
    public User createUser(UserDto.CreateRequest request) {
        Map<String, Object> errors = new LinkedHashMap<>();

        // Username: required, email format, <= 255, lowercased.
        String username = null;
        if (request == null || request.getUsername() == null || request.getUsername().isBlank()) {
            errors.put("username", Map.of("_empty", "A username is required."));
        } else {
            username = request.getUsername().trim().toLowerCase();
            if (username.length() > 255) {
                errors.put("username", Map.of("maxLength",
                        "The username length should be maximum 255 characters."));
            } else if (!EMAIL_PATTERN.matcher(username).matches()) {
                errors.put("username", Map.of("email",
                        "The username should be a valid email address."));
            } else if (userRepository.existsByUsernameAndDeletedFalse(username)) {
                errors.put("username", Map.of("uniqueUsername", "The username is already in use."));
            }
        }

        // Profile: required, first/last name required, <= 255.
        UserDto.ProfilePayload profilePayload = request != null ? request.getProfile() : null;
        if (profilePayload == null) {
            errors.put("profile", Map.of("_required", "A profile is required."));
        } else {
            Map<String, Object> profileErrors = new LinkedHashMap<>();
            validateName(profilePayload.getFirstName(), "first_name", "first name", profileErrors);
            validateName(profilePayload.getLastName(), "last_name", "last name", profileErrors);
            if (!profileErrors.isEmpty()) {
                errors.put("profile", profileErrors);
            }
        }

        // Role: defaults to user; an explicit role_id must be admin or user
        // (PHP IsAdminOrUserRoleIdRule — guest is rejected).
        Role role = null;
        String roleId = request != null ? request.getRoleId() : null;
        if (roleId == null || roleId.isBlank()) {
            role = roleRepository.findByName(Role.USER)
                    .orElseThrow(() -> new IllegalStateException("Default user role is missing."));
        } else {
            role = roleRepository.findById(roleId)
                    .filter(r -> Role.ADMIN.equals(r.getName()) || Role.USER.equals(r.getName()))
                    .orElse(null);
            if (role == null) {
                errors.put("role_id", Map.of("checkAdminOrUser", "The role must be admin or user."));
            }
        }

        if (!errors.isEmpty()) {
            throw new UserValidationException("Could not validate user data.", errors);
        }

        User user = new User();
        user.setUsername(username);
        user.setRoleId(role.getId());
        user.setActive(false);
        user.setDeleted(false);
        userRepository.save(user);

        Profile profile = new Profile();
        profile.setUserId(user.getId());
        profile.setFirstName(profilePayload.getFirstName().trim());
        profile.setLastName(profilePayload.getLastName().trim());
        profileRepository.save(profile);

        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID().toString());
        token.setType(TOKEN_TYPE_REGISTER);
        token.setActive(true);
        authenticationTokenRepository.save(token);

        log.info("User {} created (inactive), register token issued", user.getUsername());
        return user;
    }

    /**
     * Whitelist patch (PHP editEntity accessibleFields): only role_id /
     * disabled / profile.first_name / profile.last_name may change.
     * role_id only when the actor is admin; disabled only when the actor is
     * admin AND the target is not the actor (an admin disabling themselves
     * is silently ignored — PHP parity, not an error). username/active are
     * never updatable (unknown JSON keys are already dropped by Jackson).
     *
     * @return the updated user
     */
    @Transactional
    public User updateUser(String targetId, UserDto.UpdateRequest request,
            String actorId, boolean actorIsAdmin) {
        User user = userRepository.findById(targetId)
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "The user does not exist or has been deleted."));

        Map<String, Object> errors = new LinkedHashMap<>();

        // --- validation / staging phase (no persistence yet) ---

        Role newRole = null;
        if (actorIsAdmin && request.getRoleId() != null) {
            newRole = roleRepository.findById(request.getRoleId())
                    .filter(r -> Role.ADMIN.equals(r.getName()) || Role.USER.equals(r.getName()))
                    .orElse(null);
            if (newRole == null) {
                errors.put("role_id", Map.of("checkAdminOrUser", "The role must be admin or user."));
            }
        }

        boolean applyDisabled = false;
        LocalDateTime disabledValue = null;
        if (actorIsAdmin && !actorId.equals(targetId) && request.getDisabled() != null) {
            applyDisabled = true;
            if (!request.getDisabled().isBlank()) {
                disabledValue = parseDateTime(request.getDisabled());
                if (disabledValue == null) {
                    applyDisabled = false;
                    errors.put("disabled", Map.of("dateTime",
                            "The disabled date should be a valid date."));
                }
            }
            // blank string -> clear the disabled timestamp
        }

        Profile profile = null;
        if (request.getProfile() != null) {
            profile = profileRepository.findByUserId(targetId).orElseGet(() -> {
                Profile created = new Profile();
                created.setUserId(targetId);
                return created;
            });
            Map<String, Object> profileErrors = new LinkedHashMap<>();
            // Reuse the same validateName rules as createUser so both paths
            // emit consistent error keys: _empty for blank, maxLength for
            // > 255 chars (previously updateUser collapsed both into _empty).
            if (request.getProfile().getFirstName() != null) {
                if (isValidName(request.getProfile().getFirstName())) {
                    profile.setFirstName(request.getProfile().getFirstName().trim());
                } else {
                    validateName(request.getProfile().getFirstName(), "first_name", "first name",
                            profileErrors);
                }
            }
            if (request.getProfile().getLastName() != null) {
                if (isValidName(request.getProfile().getLastName())) {
                    profile.setLastName(request.getProfile().getLastName().trim());
                } else {
                    validateName(request.getProfile().getLastName(), "last_name", "last name",
                            profileErrors);
                }
            }
            // Guard against a brand-new profile row missing NOT NULL columns
            // (no value provided and none already stored).
            if (profile.getFirstName() == null && !profileErrors.containsKey("first_name")) {
                profileErrors.put("first_name", Map.of("_empty", "A first name is required."));
            }
            if (profile.getLastName() == null && !profileErrors.containsKey("last_name")) {
                profileErrors.put("last_name", Map.of("_empty", "A last name is required."));
            }
            if (!profileErrors.isEmpty()) {
                errors.put("profile", profileErrors);
            }
            // NOTE: profile.avatar is accepted but intentionally ignored on
            // this JSON patch path — avatar file upload/storage is handled by
            // AvatarController (multipart), not here. Closed boundary, not a
            // pending task.
        }

        if (!errors.isEmpty()) {
            throw new UserValidationException("Could not validate user data.", errors);
        }

        // --- apply phase ---

        if (newRole != null) {
            user.setRoleId(newRole.getId());
        }
        if (applyDisabled) {
            user.setDisabled(disabledValue);
        }
        if (profile != null) {
            profileRepository.save(profile);
        }
        return userRepository.save(user);
    }

    private void validateName(String value, String fieldKey, String label, Map<String, Object> errors) {
        if (value == null || value.isBlank()) {
            errors.put(fieldKey, Map.of("_empty", "A " + label + " is required."));
        } else if (value.length() > 255) {
            errors.put(fieldKey, Map.of("maxLength",
                    "The " + label + " length should be maximum 255 characters."));
        }
    }

    private boolean isValidName(String value) {
        return !value.isBlank() && value.length() <= 255;
    }

    /**
     * The OpenAPI spec mistakenly declares disabled as boolean; the DB
     * column is a datetime and PHP validates with dateTime — we accept an
     * ISO-8601 datetime string (with or without offset).
     */
    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Validation failure carrying the field error map rendered as the 400
     * response body (PHP "Could not validate user data." behaviour).
     * Extends IllegalArgumentException so generic controller catch blocks
     * still treat it as a 400.
     */
    public static class UserValidationException extends IllegalArgumentException {

        private final transient Map<String, Object> errors;

        public UserValidationException(String message, Map<String, Object> errors) {
            super(message);
            this.errors = errors;
        }

        public Map<String, Object> getErrors() {
            return errors;
        }
    }
}
