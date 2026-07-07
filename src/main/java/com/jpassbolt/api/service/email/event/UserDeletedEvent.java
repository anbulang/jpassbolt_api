package com.jpassbolt.api.service.email.event;

import java.util.List;

/**
 * Published by {@code UserDeleteService.deleteUser} after the deletion cascade
 * commits, consumed by BOTH {@code UserDeleteEmailRedactor} (notifies the group
 * managers of the groups the user belonged to) and {@code AdminDeleteEmailRedactor}
 * (notifies all admins when the deleted user was an admin). Port of PHP
 * {@code UsersDeleteController}'s {@code UsersDeleteController.delete.success}
 * event (payload {@code user} / {@code groupsIds} / {@code deletedBy}).
 *
 * <p>An immutable scalar snapshot rather than a JPA entity, and every field MUST
 * be captured BEFORE the destructive writes: the user is soft-deleted and all of
 * its {@code groups_users} rows are hard-deleted inside the same transaction, so
 * by the time the {@code AFTER_COMMIT} async listener runs the identity, role and
 * group membership are gone. In particular {@link #groupsIds} is the
 * <em>not-only-member</em> set (PHP {@code findGroupsWhereUserNotOnlyMember},
 * computed before the cascade) — only-member groups are deleted together with the
 * user and have no surviving managers to notify.</p>
 *
 * @param deletedUserId      the deleted user's id (drives the recipient==deleted-user branch)
 * @param deletedUsername    the deleted user's email/username (shown in both bodies)
 * @param deletedFirstName   the deleted user's profile first name, or {@code null}
 * @param deletedLastName    the deleted user's profile last name, or {@code null}
 * @param deletedUserIsAdmin whether the deleted user had the admin role (PHP {@code $deletedUser->role->isAdmin()} — {@code AdminDeleteEmailRedactor} short-circuits when false)
 * @param actorId            the admin who performed the deletion (PHP {@code deletedBy}); re-resolved to a display name, drives the "you deleted …" self-variant
 * @param groupsIds          the not-only-member groups the user belonged to (managers of these are the {@code UserDeleteEmailRedactor} recipients)
 */
public record UserDeletedEvent(
        String deletedUserId,
        String deletedUsername,
        String deletedFirstName,
        String deletedLastName,
        boolean deletedUserIsAdmin,
        String actorId,
        List<String> groupsIds) {
}
