package com.jpassbolt.api.service.email.event;

import java.util.Map;
import java.util.Set;

/**
 * Published by {@code PermissionService.share()} after a resource share commits,
 * consumed by {@code ShareEmailRedactor} to email each newly-added user. Port of
 * PHP {@code ResourcesShareService}'s {@code share.success} event consumed by
 * {@code ShareEmailRedactor}.
 *
 * <p>An immutable scalar snapshot rather than a JPA entity: the listener runs on
 * an {@code AFTER_COMMIT} async thread where entities are detached, so everything
 * the body needs is captured at publish time. The v4 plaintext metadata
 * ({@code name}/{@code username}/{@code uri}/{@code description}) is snapshotted as
 * of the share; for a v5 resource these are {@code null} (the data lives in the
 * encrypted metadata blob) and {@link #isV5} is true.</p>
 *
 * <p>{@link #addedUserIds} are the users who newly gained access — already
 * excluding the sharer, who kept their existing access and is never in the added
 * set. {@link #secretsByUserId} carries each added user's own ciphertext so the
 * (default-off) {@code show_secret} body block can render it without re-querying;
 * it is already encrypted, so carrying it on the event leaks nothing.</p>
 *
 * @param resourceId          the shared resource id (builds the SPA deep link)
 * @param resourceName        v4 plaintext name, or {@code null} for v5
 * @param resourceUsername    v4 plaintext username, or {@code null} for v5
 * @param resourceUri         v4 plaintext URI, or {@code null} for v5
 * @param resourceDescription v4 plaintext description, or {@code null} for v5
 * @param isV5                whether the resource carries a v5 encrypted metadata blob
 * @param actorId             the sharer's user id (excluded from recipients)
 * @param addedUserIds        users who newly gained access (the recipients)
 * @param secretsByUserId     per-added-user ciphertext (for the {@code show_secret} block)
 */
public record ResourceSharedEvent(
        String resourceId,
        String resourceName,
        String resourceUsername,
        String resourceUri,
        String resourceDescription,
        boolean isV5,
        String actorId,
        Set<String> addedUserIds,
        Map<String, String> secretsByUserId) {
}
