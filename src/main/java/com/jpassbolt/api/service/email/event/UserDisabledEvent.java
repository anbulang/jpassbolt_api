package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code UserService.updateUser} when an edit transitions a user's
 * {@code disabled} timestamp from null to non-null (PHP {@code $isBeingDisabled}
 * in {@code UsersEditController}), consumed by BOTH {@code UserDisableEmailRedactor}
 * (notifies all administrators) and {@code AdminDisableEmailRedactor} (notifies the
 * suspended user themselves when they are an admin) — the port of the
 * {@code EVENT_USER_WAS_DISABLED} / {@code EVENT_ADMIN_WAS_DISABLED} pair.
 *
 * <p>An immutable scalar snapshot rather than a JPA entity: the suspended user is
 * now dropped by {@code RecipientResolver} (it filters disabled users), so the
 * admin-variant redactor must build its recipient from these captured fields —
 * the Java equivalent of PHP's "set disabled to tomorrow so the email still goes
 * out" workaround in {@code AdminDisableEmailRedactor}.</p>
 *
 * @param disabledUserId      the suspended user's id
 * @param disabledUsername    the suspended user's email/username (mail destination for the admin variant)
 * @param disabledFirstName   the suspended user's profile first name, or {@code null}
 * @param disabledLastName    the suspended user's profile last name, or {@code null}
 * @param disabledUserIsAdmin whether the suspended user holds the admin role after this edit (PHP {@code $user->role->name === Role::ADMIN} on the post-save re-read — gates the admin variant)
 * @param actorId             the admin who performed the suspension (PHP {@code operator}); re-resolved to a display name/contact address in the bodies
 */
public record UserDisabledEvent(
        String disabledUserId,
        String disabledUsername,
        String disabledFirstName,
        String disabledLastName,
        boolean disabledUserIsAdmin,
        String actorId) {
}
