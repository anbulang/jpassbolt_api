package com.jpassbolt.api.service.email.event;

import java.util.List;

/**
 * Published by {@code GroupService.createGroup} after a group commits, consumed by
 * {@code GroupUserAddEmailRedactor} to email each initial member that they were
 * added. Port of PHP {@code GroupsTable::GROUP_CREATE_SUCCESS_EVENT}, which the PHP
 * {@code GroupUserAddEmailRedactor} also subscribes to.
 *
 * <p>An immutable scalar snapshot (no JPA entities): the listener runs on an
 * {@code AFTER_COMMIT} async thread. The actor ({@link #actorId}, the creator ==
 * {@code created_by}) is excluded from the recipients (PHP skips
 * {@code group_user.user_id === group.created_by}).</p>
 *
 * @param groupId   the created group id (deep link + recipient resolution)
 * @param groupName the group name (subject/body)
 * @param actorId   the creating admin (== created_by); excluded from recipients, shown as the actor name
 * @param members   the initial memberships ({userId, isAdmin}); recipients are these minus the actor
 */
public record GroupCreatedEvent(
        String groupId,
        String groupName,
        String actorId,
        List<GroupMemberSnapshot> members) {
}
