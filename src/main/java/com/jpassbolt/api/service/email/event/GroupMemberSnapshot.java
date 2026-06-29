package com.jpassbolt.api.service.email.event;

/**
 * Scalar snapshot of one affected group membership — the {@code user_id} and the
 * {@code is_admin} (manager) flag — carried inside {@link GroupCreatedEvent} and
 * {@link GroupMembershipChangedEvent}. Avoids leaking a detached JPA
 * {@code GroupUser} entity onto the {@code AFTER_COMMIT} async listener thread; for
 * removed members it must be captured BEFORE the row is deleted.
 *
 * @param userId  the affected member's user id
 * @param isAdmin whether the membership is (for add/update: becomes; for remove: was) a group manager
 */
public record GroupMemberSnapshot(String userId, boolean isAdmin) {
}
