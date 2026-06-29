package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code UserService.createUser} (admin invite AND guest
 * self-registration) and {@code RecoverService.recover} (the register-token
 * restart branch for a not-yet-active user), consumed by
 * {@code UserRegisterEmailRedactor} to email the new user a setup link. Port of
 * PHP {@code UserRegisterEmailRedactor}'s {@code Model.Users.afterRegister.success}
 * event (payload {@code user}/{@code token}/{@code adminId}).
 *
 * <p>An immutable scalar snapshot rather than a JPA entity: the listener runs on
 * an {@code AFTER_COMMIT} async thread where the User / Profile /
 * AuthenticationToken entities are detached, so the new user's name, the register
 * token value and the actor are all captured at publish time.</p>
 *
 * <p>The setup-link recipient is the new user ({@link #userId}); the admin actor
 * ({@link #adminId}) is only resolved to a display name for the body and is never
 * mailed. {@link #adminId} is {@code null} on both the self-driven recover-restart
 * branch and guest self-registration (no admin actor) — the body then omits the
 * admin attribution line. {@link #disabled} mirrors PHP's {@code !user->isDisabled()}
 * guard: when true the redactor sends nothing.</p>
 *
 * <p>{@link #selfRegistration} is the discriminator that distinguishes a guest
 * self-registration (PHP {@code Model.Users.afterSelfRegister.success}) from an
 * admin invite / recover-restart: it is {@code true} only on the public
 * self-registration path. The welcome/setup email fires for ALL three sources,
 * but {@code SelfRegistrationAdminEmailRedactor} (the "a user just created an
 * account" admin notice) listens to the same event and short-circuits unless this
 * flag is set, so the admin notice is never sent for an admin-driven create or a
 * recover restart. {@code adminId == null} alone cannot carry this distinction
 * because the recover-restart branch is also {@code adminId == null}.</p>
 *
 * @param userId           new user id — builds the {@code /setup/{userId}/{token}} link and is the setup-mail recipient
 * @param username         new user's email (== username); greeting/address fallback
 * @param firstName        new user's profile first name (greeting/subject), or {@code null}
 * @param lastName         new user's profile last name, or {@code null}
 * @param token            the register {@code AuthenticationToken} value (second half of the setup link)
 * @param adminId          the admin actor id (resolved to a display name in the body), or {@code null} on the recover-restart / self-registration branches
 * @param disabled         whether the new user is disabled at publish time (PHP {@code !user->isDisabled()} send guard)
 * @param selfRegistration {@code true} only for a guest self-registration — gates the admin "new account" notice
 */
public record UserRegisteredEvent(
        String userId,
        String username,
        String firstName,
        String lastName,
        String token,
        String adminId,
        boolean disabled,
        boolean selfRegistration) {
}
