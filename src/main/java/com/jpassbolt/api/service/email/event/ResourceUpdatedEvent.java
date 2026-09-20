package com.jpassbolt.api.service.email.event;

import java.util.Map;

/**
 * Published by {@code ResourceService.updateResource()} after a resource update
 * commits, consumed by {@code ResourceUpdateEmailRedactor}. Port of PHP
 * {@code ResourcesUpdateService::UPDATE_SUCCESS_EVENT_NAME} consumed by
 * {@code ResourceUpdateEmailRedactor} (gate {@code send.password.update},
 * default ON).
 *
 * <p>Recipients are <em>everyone with access</em> to the resource, the operator
 * included (they get the "You edited …" wording, others get "{actor} edited …").
 * An update neither soft-deletes the resource nor removes its permissions, so the
 * recipient set is re-resolved in the redactor at {@code AFTER_COMMIT} time,
 * exactly as PHP's redactor queries {@code has-access} — only the scalar resource
 * snapshot and the freshly-written secrets travel on the event.</p>
 *
 * <p>{@link #secretsByUserId} maps each user whose ciphertext was re-written by
 * this update to that ciphertext (empty when only metadata changed), so the
 * default-off {@code show_secret} block can show each recipient <em>their own</em>
 * secret and no one else's.</p>
 *
 * @param resourceId          the updated resource id (builds the SPA deep link)
 * @param resourceName        v4 plaintext name, or {@code null} for v5
 * @param resourceUsername    v4 plaintext username, or {@code null} for v5
 * @param resourceUri         v4 plaintext URI, or {@code null} for v5
 * @param resourceDescription v4 plaintext description, or {@code null} for v5
 * @param isV5                whether the resource carries a v5 encrypted metadata blob
 * @param actorId             the editor's user id (kept, gets the "you" wording)
 * @param secretsByUserId     per-user re-written ciphertext (for {@code show_secret})
 */
public record ResourceUpdatedEvent(
        String resourceId,
        String resourceName,
        String resourceUsername,
        String resourceUri,
        String resourceDescription,
        boolean isV5,
        String actorId,
        Map<String, String> secretsByUserId) {
}
