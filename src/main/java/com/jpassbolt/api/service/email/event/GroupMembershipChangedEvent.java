package com.jpassbolt.api.service.email.event;

import java.util.List;

/**
 * Published by {@code GroupService.updateGroup} after a group update commits, and
 * consumed by ALL FOUR group-update redactors — {@code GroupUserAddEmailRedactor}
 * (added members), {@code GroupUserDeleteEmailRedactor} (removed members),
 * {@code GroupUserUpdateEmailRedactor} (role-changed members) and
 * {@code GroupUpdateAdminSummaryEmailRedactor} (the other current managers). Port
 * of PHP {@code GroupsUpdateService::UPDATE_SUCCESS_EVENT_NAME} (payload
 * {@code group} + {@code entitiesChanges} added/updated/deleted + {@code userId}),
 * fanned out to the same four PHP redactors.
 *
 * <p>An immutable scalar snapshot (no JPA entities): the listener runs on an
 * {@code AFTER_COMMIT} async thread. {@link #removed} MUST be captured BEFORE
 * {@code groupUserRepository.deleteAll(toDelete)} (the rows are gone afterwards),
 * and {@link #groupName} MUST be the POST-rename name (the rename happens in the
 * same {@code updateGroup} transaction; the summary subject reads
 * "{actor} updated the group {name}"). Each {@link GroupMemberSnapshot} carries the
 * member's new role for add/update and its role-at-removal for removed.</p>
 *
 * @param groupId   the updated group id (deep link + manager resolution)
 * @param groupName the POST-rename group name (subject/body)
 * @param actorId   the operator (PHP {@code userId} / {@code modifiedBy}); excluded from every recipient set
 * @param added     memberships newly added in this update
 * @param removed   memberships removed in this update (snapshotted before the delete)
 * @param updated   memberships whose manager role changed in this update
 */
public record GroupMembershipChangedEvent(
        String groupId,
        String groupName,
        String actorId,
        List<GroupMemberSnapshot> added,
        List<GroupMemberSnapshot> removed,
        List<GroupMemberSnapshot> updated) {
}
