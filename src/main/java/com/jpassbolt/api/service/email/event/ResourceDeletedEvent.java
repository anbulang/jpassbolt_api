package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code ResourceService.deleteResource()} after a resource
 * (soft-)delete commits, consumed by {@code ResourceDeleteEmailRedactor}. Port of
 * PHP {@code ResourcesDeleteController::DELETE_SUCCESS_EVENT_NAME} consumed by
 * {@code ResourceDeleteEmailRedactor} (gate {@code send.password.delete},
 * default ON).
 *
 * <p>Recipients are everyone with access <em>except the deleter</em> (PHP removes
 * the deleter from {@code $users} before the redactor runs). Because the delete is
 * a soft delete — the resource row stays (flagged {@code deleted}) and its
 * permission rows are untouched — the recipient set is re-resolved in the redactor
 * at {@code AFTER_COMMIT} time and the actor is filtered out there, the same
 * pattern as the update event (no service-side snapshot needed). Disabled users
 * are dropped by the resolver. No secret is offered on a delete (the password is
 * gone); the body's default-off disclosures cover username/URI/description only.</p>
 *
 * @param resourceId          the deleted resource id (informational; no live link)
 * @param resourceName        v4 plaintext name, or {@code null} for v5
 * @param resourceUsername    v4 plaintext username, or {@code null} for v5
 * @param resourceUri         v4 plaintext URI, or {@code null} for v5
 * @param resourceDescription v4 plaintext description, or {@code null} for v5
 * @param isV5                whether the resource carried a v5 encrypted metadata blob
 * @param actorId             the deleter's user id (excluded from recipients)
 */
public record ResourceDeletedEvent(
        String resourceId,
        String resourceName,
        String resourceUsername,
        String resourceUri,
        String resourceDescription,
        boolean isV5,
        String actorId) {
}
