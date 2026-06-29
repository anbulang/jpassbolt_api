package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code RecoverService.completeRecover} after the recover token is
 * consumed and the transaction commits, consumed by
 * {@code RecoverCompleteUserEmailRedactor} (confirmation to the recovering user)
 * and {@code RecoverCompleteAdminEmailRedactor} (security notice to all other
 * active admins). Port of PHP {@code RecoverCompleteServiceInterface::COMPLETE_SUCCESS_EVENT_NAME}
 * (payload {@code user} / {@code clientIp} / {@code userAgent}).
 *
 * <p>An immutable scalar snapshot (no JPA entities): the listeners run on an
 * {@code AFTER_COMMIT} async thread. {@link #clientIp} and {@link #userAgent} are
 * request-scoped and therefore captured in the controller and threaded down — they
 * are unavailable on the async thread. Account recovery changes no user state
 * (PHP "we do not update anything"), so the identity fields are stable across the
 * operation. This is the one place where the actor (the recovering user) is also a
 * legitimate recipient: the user redactor targets exactly them, while the admin
 * redactor filters them out.</p>
 *
 * @param userId        the recovering user's id (user recipient; excluded from the admin recipients; deep link)
 * @param username      the recovering user's email/username (admin body "firstName (username)")
 * @param userFirstName the recovering user's profile first name (admin subject/body), or {@code null}
 * @param clientIp      the client IP at completion ({@code HttpServletRequest.getRemoteAddr()}), may be empty
 * @param userAgent     the client User-Agent at completion, or {@code null}
 */
public record RecoverCompletedEvent(
        String userId,
        String username,
        String userFirstName,
        String clientIp,
        String userAgent) {
}
