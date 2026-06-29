package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code UserService.createUser} (admin invite) and
 * {@code RecoverService.recover} (the register-token restart branch for a
 * not-yet-active user), consumed by {@code UserRegisterEmailRedactor} to email
 * the invited user a setup link. Port of PHP {@code UserRegisterEmailRedactor}'s
 * {@code Model.Users.afterRegister.success} event (payload
 * {@code user}/{@code token}/{@code adminId}).
 *
 * <p>An immutable scalar snapshot rather than a JPA entity: the listener runs on
 * an {@code AFTER_COMMIT} async thread where the User / Profile /
 * AuthenticationToken entities are detached, so the invited user's name, the
 * register token value and the actor are all captured at publish time.</p>
 *
 * <p>The single recipient is the invited user ({@link #userId}); the admin actor
 * ({@link #adminId}) is only resolved to a display name for the body and is never
 * mailed. {@link #adminId} is {@code null} on the self-driven recover-restart
 * branch (no admin actor) — the body then omits the admin attribution line.
 * {@link #disabled} mirrors PHP's {@code !user->isDisabled()} guard: when true the
 * redactor sends nothing.</p>
 *
 * @param userId    invited user id — builds the {@code /setup/{userId}/{token}} link and is the sole recipient
 * @param username  invited user's email (== username); greeting/address fallback
 * @param firstName invited user's profile first name (greeting/subject), or {@code null}
 * @param lastName  invited user's profile last name, or {@code null}
 * @param token     the register {@code AuthenticationToken} value (second half of the setup link)
 * @param adminId   the admin actor id (resolved to a display name in the body), or {@code null} on the recover-restart branch
 * @param disabled  whether the invited user is disabled at publish time (PHP {@code !user->isDisabled()} send guard)
 */
public record UserRegisteredEvent(
        String userId,
        String username,
        String firstName,
        String lastName,
        String token,
        String adminId,
        boolean disabled) {
}
