package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code ResourceService.createResource()} after a resource create
 * commits, consumed by {@code ResourceCreateEmailRedactor}. Port of PHP
 * {@code ResourcesAddService::ADD_SUCCESS_EVENT_NAME} consumed by
 * {@code ResourceCreateEmailRedactor} (gate {@code send.password.create},
 * default OFF).
 *
 * <p>The sole recipient is the creator themselves — a "you added the password X"
 * confirmation. An immutable scalar snapshot rather than a JPA entity: the
 * {@code AFTER_COMMIT} listener runs detached, so the v4 plaintext metadata is
 * captured at publish time (and is {@code null} for a v5 resource — {@link #isV5}
 * is then true and the subject/body use the generic wording). {@link #creatorSecret}
 * carries the creator's own ciphertext so the default-off {@code show_secret} body
 * block can render it without a re-query; it is already encrypted, so carrying it
 * leaks nothing.</p>
 *
 * @param resourceId          the created resource id (builds the SPA deep link)
 * @param resourceName        v4 plaintext name, or {@code null} for v5
 * @param resourceUsername    v4 plaintext username, or {@code null} for v5
 * @param resourceUri         v4 plaintext URI, or {@code null} for v5
 * @param resourceDescription v4 plaintext description, or {@code null} for v5
 * @param isV5                whether the resource carries a v5 encrypted metadata blob
 * @param actorId             the creator's user id (also the only recipient)
 * @param creatorSecret       the creator's own ciphertext (for {@code show_secret}), may be null
 */
public record ResourceCreatedEvent(
        String resourceId,
        String resourceName,
        String resourceUsername,
        String resourceUri,
        String resourceDescription,
        boolean isV5,
        String actorId,
        String creatorSecret) {
}
