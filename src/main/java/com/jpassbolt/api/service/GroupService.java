package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.GroupDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GroupService implements the group management business logic, ported from
 * the CakePHP reference (GroupsAdd/Update/Delete controllers and
 * GroupsUpdateService / GroupsUpdateDryRunService):
 *
 * <ul>
 * <li>Only administrators can create groups (enforced by the controller).</li>
 * <li>Only group managers can ADD members; additions requested by a
 * non-manager operator are silently ignored (PHP GroupsUpdateService
 * '@note requested additions will be ignored' + addGroupsUsers
 * isUacManager gate). Removals and is_admin changes carry NO manager
 * gate in PHP (updateGroupsUsers / deleteGroupsUsers) — the controller
 * only requires manager-or-admin, so an organization admin can remove
 * members. Admins can rename the group.</li>
 * <li>Adding a member requires the client to provide the member's encrypted
 * secrets for every resource the group has access to and the member
 * cannot access otherwise; the dry-run endpoint reports this list
 * (SecretsNeeded) plus the operator's own secrets to re-encrypt.</li>
 * <li>A group cannot be deleted while it is the sole owner of a shared
 * resource.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupUserRepository groupUserRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final SecretRepository secretRepository;
    private final ResourceRepository resourceRepository;
    private final FavoriteRepository favoriteRepository;

    // ------------------------------------------------------------------
    // Read operations
    // ------------------------------------------------------------------

    /**
     * Get all non-deleted groups ordered by name, with optional
     * filter[has-users] / filter[has-managers] (comma-separated user ids,
     * "all of" semantics like the PHP reference).
     */
    @Transactional(readOnly = true)
    public List<Group> getGroups(String hasUsers, String hasManagers) {
        List<Group> groups = groupRepository.findByDeletedFalseOrderByNameAsc();

        if (hasUsers != null && !hasUsers.isBlank()) {
            Set<String> required = splitCsv(hasUsers);
            groups = groups.stream()
                    .filter(g -> groupUserRepository.findByGroupId(g.getId()).stream()
                            .map(GroupUser::getUserId)
                            .collect(Collectors.toSet())
                            .containsAll(required))
                    .collect(Collectors.toList());
        }

        if (hasManagers != null && !hasManagers.isBlank()) {
            Set<String> required = splitCsv(hasManagers);
            groups = groups.stream()
                    .filter(g -> groupUserRepository.findByGroupIdAndIsAdminTrue(g.getId()).stream()
                            .map(GroupUser::getUserId)
                            .collect(Collectors.toSet())
                            .containsAll(required))
                    .collect(Collectors.toList());
        }

        return groups;
    }

    /**
     * Get a non-deleted group by id.
     */
    @Transactional(readOnly = true)
    public Optional<Group> getGroupById(String id) {
        return groupRepository.findById(id)
                .filter(g -> !Boolean.TRUE.equals(g.getDeleted()));
    }

    /**
     * Get all memberships of a group.
     */
    @Transactional(readOnly = true)
    public List<GroupUser> getGroupUsers(String groupId) {
        return groupUserRepository.findByGroupId(groupId);
    }

    /**
     * Get the membership of a user in a group, if any (my_group_user).
     */
    @Transactional(readOnly = true)
    public Optional<GroupUser> getMyGroupUser(String groupId, String userId) {
        return groupUserRepository.findByGroupIdAndUserId(groupId, userId);
    }

    /**
     * Check whether a user has the admin role. The role is resolved from the
     * database and compared by role name (never by hard-coded UUID).
     */
    @Transactional(readOnly = true)
    public boolean isAdmin(String userId) {
        return userRepository.findById(userId)
                .flatMap(u -> roleRepository.findById(u.getRoleId()))
                .map(role -> Role.ADMIN.equals(role.getName()))
                .orElse(false);
    }

    /**
     * Check whether a user is a manager (is_admin membership) of a group.
     */
    @Transactional(readOnly = true)
    public boolean isGroupManager(String groupId, String userId) {
        return groupUserRepository.existsByGroupIdAndUserIdAndIsAdminTrue(groupId, userId);
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    /**
     * Create a group with its initial members. At least one member must be a
     * group manager (is_admin = true). The admin-role guard is enforced by
     * the controller.
     */
    @Transactional
    public Group createGroup(GroupDto.CreateRequest request, String operatorId) {
        String name = request.getName();
        if (name == null || name.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "The group name is required.");
        }
        if (groupRepository.existsByNameAndDeletedFalse(name)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The group name is already used by another group.");
        }
        List<GroupDto.GroupUserData> groupsUsers = request.getGroupsUsers();
        if (groupsUsers == null || groupsUsers.isEmpty()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "A group manager must be provided.");
        }
        boolean hasManager = groupsUsers.stream()
                .anyMatch(gu -> Boolean.TRUE.equals(gu.getIsAdmin()));
        if (!hasManager) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "A group manager must be provided.");
        }
        Set<String> seen = new HashSet<>();
        for (GroupDto.GroupUserData guData : groupsUsers) {
            if (guData.getUserId() == null || guData.getUserId().isBlank()) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "A user identifier is required for each group member.");
            }
            if (!seen.add(guData.getUserId())) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "A user cannot be added multiple times to the group.");
            }
            assertUserCanBeMember(guData.getUserId());
        }

        Group group = new Group();
        group.setName(name);
        group.setDeleted(false);
        group.setCreatedBy(operatorId);
        group.setModifiedBy(operatorId);
        groupRepository.save(group);

        for (GroupDto.GroupUserData guData : groupsUsers) {
            GroupUser gu = new GroupUser();
            gu.setGroupId(group.getId());
            gu.setUserId(guData.getUserId());
            gu.setIsAdmin(Boolean.TRUE.equals(guData.getIsAdmin()));
            groupUserRepository.save(gu);
        }

        log.info("Group '{}' ({}) created by {}", name, group.getId(), operatorId);
        return group;
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    /**
     * Update a group: rename (admins only, silently ignored otherwise like
     * the PHP reference), change manager roles (managers and admins) and
     * add/remove members (managers only, silently ignored otherwise).
     * Added members must come with the encrypted secrets reported by the
     * dry-run endpoint; removed members lose the secrets of the resources
     * they could only access through this group.
     */
    @Transactional
    public Group updateGroup(String groupId, GroupDto.UpdateRequest request, String operatorId,
            boolean operatorIsAdmin, boolean operatorIsManager) {
        Group group = getGroupOrFail(groupId);

        List<GroupDto.GroupUserChange> changes = request.getGroupsUsers() != null
                ? request.getGroupsUsers()
                : List.of();
        List<GroupDto.SecretData> providedSecrets = request.getSecrets() != null
                ? request.getSecrets()
                : List.of();

        List<GroupUser> currentMembers = groupUserRepository.findByGroupId(groupId);
        Map<String, GroupUser> membersById = currentMembers.stream()
                .collect(Collectors.toMap(GroupUser::getId, gu -> gu, (a, b) -> a));
        Map<String, GroupUser> membersByUserId = currentMembers.stream()
                .collect(Collectors.toMap(GroupUser::getUserId, gu -> gu, (a, b) -> a));

        List<GroupUser> toAdd = new ArrayList<>();
        List<GroupUser> toDelete = new ArrayList<>();
        List<GroupUser> toUpdate = new ArrayList<>();

        for (GroupDto.GroupUserChange change : changes) {
            GroupUser existing = resolveExistingMember(change, membersById, membersByUserId);
            if (existing == null) {
                // Addition: silently ignored when the operator is not a
                // group manager (PHP GroupsUpdateService::addGroupsUsers).
                if (!operatorIsManager) {
                    continue;
                }
                String userIdToAdd = change.getUserId();
                assertUserCanBeMember(userIdToAdd);
                boolean alreadyQueued = toAdd.stream()
                        .anyMatch(gu -> gu.getUserId().equals(userIdToAdd));
                if (alreadyQueued) {
                    throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                            "A user cannot be added multiple times to the group.");
                }
                GroupUser gu = new GroupUser();
                gu.setGroupId(groupId);
                gu.setUserId(userIdToAdd);
                gu.setIsAdmin(Boolean.TRUE.equals(change.getIsAdmin()));
                toAdd.add(gu);
            } else if (Boolean.TRUE.equals(change.getDelete())) {
                // Removal: NO manager gate (PHP deleteGroupsUsers has none —
                // the controller already requires manager-or-admin, so an
                // organization admin may remove members too).
                toDelete.add(existing);
            } else if (change.getIsAdmin() != null
                    && !change.getIsAdmin().equals(existing.getIsAdmin())) {
                // Manager-role change: managers and admins.
                existing.setIsAdmin(change.getIsAdmin());
                toUpdate.add(existing);
            }
        }

        assertAtLeastOneManagerRemains(currentMembers, toAdd, toDelete);

        // The secrets required for the added members must all be provided.
        List<Map<String, String>> missing = new ArrayList<>();
        for (GroupUser gu : toAdd) {
            missing.addAll(computeMissingSecrets(groupId, gu.getUserId()));
        }
        for (Map<String, String> pair : missing) {
            findProvidedSecret(providedSecrets, pair)
                    .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                            "A secret is missing for the resource " + pair.get("resource_id")
                                    + " and the user " + pair.get("user_id") + "."));
        }

        // Apply the membership changes.
        groupUserRepository.deleteAll(toDelete);
        groupUserRepository.saveAll(toUpdate);
        groupUserRepository.saveAll(toAdd);

        // Persist the provided secrets covering the missing ones.
        for (Map<String, String> pair : missing) {
            if (secretRepository
                    .findByResourceIdAndUserId(pair.get("resource_id"), pair.get("user_id"))
                    .isPresent()) {
                continue;
            }
            GroupDto.SecretData data = findProvidedSecret(providedSecrets, pair).orElseThrow();
            Secret secret = new Secret();
            secret.setResourceId(pair.get("resource_id"));
            secret.setUserId(pair.get("user_id"));
            secret.setData(data.getData());
            secretRepository.save(secret);
        }

        // Clean up the secrets AND favorites of the removed members for the
        // resources they could only access through this group (PHP
        // GroupsUsersDeleteService deletes both in the same transaction).
        List<String> groupResourceIds = getGroupResourceIds(groupId);
        for (GroupUser removed : toDelete) {
            for (String resourceId : groupResourceIds) {
                if (!userHasAccessElsewhere(resourceId, removed.getUserId(), groupId)) {
                    secretRepository.findByResourceIdAndUserId(resourceId, removed.getUserId())
                            .ifPresent(secretRepository::delete);
                    favoriteRepository.findByUserIdAndForeignKey(removed.getUserId(), resourceId)
                            .ifPresent(favoriteRepository::delete);
                }
            }
        }

        // Rename: administrators only; silently ignored otherwise (PHP
        // GroupsUpdateService::updateMetaData).
        String newName = request.getName();
        if (operatorIsAdmin && newName != null && !newName.isBlank()
                && !newName.equals(group.getName())) {
            if (groupRepository.existsByNameAndDeletedFalse(newName)) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The group name is already used by another group.");
            }
            group.setName(newName);
        }

        group.setModifiedBy(operatorId);
        return groupRepository.save(group);
    }

    /**
     * Dry run of a group update: compute, for every member addition in the
     * changes, the secrets the client will have to encrypt (SecretsNeeded)
     * and return the operator's own secrets for those resources so the client
     * can decrypt and re-encrypt them. Mirrors GroupsUpdateDryRunService:
     * when the operator is not a group manager both lists stay empty.
     *
     * @return map with keys "secretsNeeded" (List of {resource_id, user_id}
     *         maps) and "secrets" (List of the operator's {@link Secret}s)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> updateDryRun(String groupId, List<GroupDto.GroupUserChange> changes,
            String operatorId, boolean operatorIsManager) {
        getGroupOrFail(groupId);

        List<Map<String, String>> secretsNeeded = new ArrayList<>();
        List<Secret> operatorSecrets = new ArrayList<>();

        if (operatorIsManager && changes != null) {
            List<GroupUser> currentMembers = groupUserRepository.findByGroupId(groupId);
            Map<String, GroupUser> membersById = currentMembers.stream()
                    .collect(Collectors.toMap(GroupUser::getId, gu -> gu, (a, b) -> a));
            Map<String, GroupUser> membersByUserId = currentMembers.stream()
                    .collect(Collectors.toMap(GroupUser::getUserId, gu -> gu, (a, b) -> a));

            for (GroupDto.GroupUserChange change : changes) {
                GroupUser existing = resolveExistingMember(change, membersById, membersByUserId);
                if (existing != null) {
                    continue;
                }
                assertUserCanBeMember(change.getUserId());
                secretsNeeded.addAll(computeMissingSecrets(groupId, change.getUserId()));
            }

            Set<String> resourceIds = secretsNeeded.stream()
                    .map(pair -> pair.get("resource_id"))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String resourceId : resourceIds) {
                secretRepository.findByResourceIdAndUserId(resourceId, operatorId)
                        .ifPresent(operatorSecrets::add);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("secretsNeeded", secretsNeeded);
        result.put("secrets", operatorSecrets);
        return result;
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    /**
     * Find the non-deleted resources blocking the deletion of a group: the
     * shared resources (at least one other permission exists) for which the
     * group holds the only OWNER permission. Mirrors
     * PermissionsTable::findSharedAcosByAroIsSoleOwner.
     */
    @Transactional(readOnly = true)
    public List<Resource> findSoleOwnerBlockingResources(String groupId) {
        List<Resource> blocking = new ArrayList<>();
        List<Permission> ownerPermissions = permissionRepository
                .findByAroAndAroForeignKey(Permission.GROUP_ARO, groupId).stream()
                .filter(p -> Permission.RESOURCE_ACO.equals(p.getAco())
                        && p.getType() == Permission.OWNER)
                .collect(Collectors.toList());

        for (Permission ownerPermission : ownerPermissions) {
            List<Permission> all = permissionRepository
                    .findByResourceId(ownerPermission.getAcoForeignKey());
            boolean shared = all.stream()
                    .anyMatch(p -> !p.getId().equals(ownerPermission.getId()));
            boolean otherOwner = all.stream()
                    .anyMatch(p -> !p.getId().equals(ownerPermission.getId())
                            && p.getType() == Permission.OWNER);
            if (shared && !otherOwner) {
                resourceRepository.findById(ownerPermission.getAcoForeignKey())
                        .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                        .ifPresent(blocking::add);
            }
        }
        return blocking;
    }

    /**
     * Get the non-deleted resources the group has a permission on (used by
     * the DELETE dry-run success body, like ResourcesTable::findAllByGroupAccess).
     */
    @Transactional(readOnly = true)
    public List<Resource> getGroupAccessibleResources(String groupId) {
        return getGroupResourceIds(groupId).stream()
                .map(resourceRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * Delete a group with an optional ownership transfer, everything in ONE
     * transaction (PHP GroupsDeleteController::delete: _transferContentOwners
     * → _validateDelete → softDelete). Throws
     * {@link GroupSoleOwnerConflictException} (→ 400) when the group remains
     * the sole owner of shared content after the transfer.
     */
    @Transactional
    public void deleteGroup(String groupId, String operatorId, GroupDto.DeleteRequest request) {
        getGroupOrFail(groupId);

        if (request != null && request.getTransfer() != null
                && request.getTransfer().getOwners() != null
                && !request.getTransfer().getOwners().isEmpty()) {
            transferContentOwners(groupId, request.getTransfer().getOwners());
        }

        List<Resource> blocking = findSoleOwnerBlockingResources(groupId);
        if (!blocking.isEmpty()) {
            throw new GroupSoleOwnerConflictException(blocking);
        }

        deleteGroup(groupId, operatorId);
    }

    /**
     * PHP GroupsDeleteController::_transferContentOwners: the transferred
     * resource set must exactly equal the blocking set (sorted comparison);
     * each referenced permission — scoped to the blocking resources — is then
     * promoted to OWNER(15).
     */
    private void transferContentOwners(String groupId, List<GroupDto.TransferOwner> owners) {
        for (GroupDto.TransferOwner owner : owners) {
            if (owner.getId() == null || !isUuid(owner.getId())) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The permissions identifiers must be valid UUID.");
            }
        }
        Set<String> blocking = findSoleOwnerBlockingResources(groupId).stream()
                .map(Resource::getId)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        Set<String> transferred = owners.stream()
                .map(GroupDto.TransferOwner::getAcoForeignKey)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!blocking.equals(transferred)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "The transfer is not authorized");
        }
        for (GroupDto.TransferOwner owner : owners) {
            Permission permission = permissionRepository.findById(owner.getId())
                    .filter(p -> p.getAcoForeignKey().equals(owner.getAcoForeignKey())
                            && blocking.contains(p.getAcoForeignKey())
                            && !(Permission.GROUP_ARO.equals(p.getAro())
                                    && groupId.equals(p.getAroForeignKey())))
                    .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                            "The transfer is not authorized"));
            permission.setType(Permission.OWNER);
            permissionRepository.save(permission);
        }
    }

    /**
     * Delete a group: soft-delete the group row, remove its memberships and
     * its permissions, soft-delete the resources that end up with no
     * permission at all, and delete the secrets of the members who lose
     * their access. The caller must have validated
     * {@link #findSoleOwnerBlockingResources(String)} is empty beforehand.
     */
    @Transactional
    public void deleteGroup(String groupId, String operatorId) {
        Group group = getGroupOrFail(groupId);

        List<GroupUser> members = groupUserRepository.findByGroupId(groupId);
        List<Permission> groupPermissions = permissionRepository
                .findByAroAndAroForeignKey(Permission.GROUP_ARO, groupId);
        List<String> resourceIds = groupPermissions.stream()
                .filter(p -> Permission.RESOURCE_ACO.equals(p.getAco()))
                .map(Permission::getAcoForeignKey)
                .distinct()
                .collect(Collectors.toList());

        permissionRepository.deleteAll(groupPermissions);
        groupUserRepository.deleteAll(members);

        group.setDeleted(true);
        group.setModifiedBy(operatorId);
        groupRepository.save(group);

        for (String resourceId : resourceIds) {
            List<Permission> remaining = permissionRepository.findByResourceId(resourceId);
            if (remaining.isEmpty()) {
                // Orphan resource: the group permission was the only one.
                resourceRepository.findById(resourceId)
                        .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                        .ifPresent(r -> {
                            r.setDeleted(true);
                            r.setModifiedBy(operatorId);
                            resourceRepository.save(r);
                        });
                secretRepository.deleteAll(secretRepository.findByResourceId(resourceId));
            } else {
                for (GroupUser member : members) {
                    if (!userHasAccessElsewhere(resourceId, member.getUserId(), groupId)) {
                        secretRepository
                                .findByResourceIdAndUserId(resourceId, member.getUserId())
                                .ifPresent(secretRepository::delete);
                    }
                }
            }
        }

        log.info("Group {} deleted by {}", groupId, operatorId);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Group getGroupOrFail(String groupId) {
        return groupRepository.findById(groupId)
                .filter(g -> !Boolean.TRUE.equals(g.getDeleted()))
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The group does not exist."));
    }

    /**
     * Resolve the existing membership a change refers to, or null when the
     * change is an addition. An explicit id that does not belong to the
     * group is a validation error.
     */
    private GroupUser resolveExistingMember(GroupDto.GroupUserChange change,
            Map<String, GroupUser> membersById, Map<String, GroupUser> membersByUserId) {
        if (change.getId() != null && !change.getId().isBlank()) {
            GroupUser existing = membersById.get(change.getId());
            if (existing == null) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The group user does not exist: " + change.getId() + ".");
            }
            return existing;
        }
        if (change.getUserId() != null && !change.getUserId().isBlank()) {
            return membersByUserId.get(change.getUserId());
        }
        throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                "A group user change must provide an id or a user_id.");
    }

    private void assertUserCanBeMember(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "A user identifier is required for each group member.");
        }
        userRepository.findById(userId)
                .filter(u -> Boolean.TRUE.equals(u.getActive())
                        && !Boolean.TRUE.equals(u.getDeleted()))
                .orElseThrow(() -> new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "Cannot find the user: " + userId + "."));
    }

    private void assertAtLeastOneManagerRemains(List<GroupUser> currentMembers,
            List<GroupUser> toAdd, List<GroupUser> toDelete) {
        Set<String> deletedIds = toDelete.stream()
                .map(GroupUser::getId)
                .collect(Collectors.toSet());
        boolean hasManager = currentMembers.stream()
                .filter(gu -> !deletedIds.contains(gu.getId()))
                .anyMatch(gu -> Boolean.TRUE.equals(gu.getIsAdmin()))
                || toAdd.stream().anyMatch(gu -> Boolean.TRUE.equals(gu.getIsAdmin()));
        if (!hasManager) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The group must have at least one group manager.");
        }
    }

    /**
     * The ids of the non-deleted resources the group has a permission on.
     */
    private List<String> getGroupResourceIds(String groupId) {
        return permissionRepository.findByAroAndAroForeignKey(Permission.GROUP_ARO, groupId)
                .stream()
                .filter(p -> Permission.RESOURCE_ACO.equals(p.getAco()))
                .map(Permission::getAcoForeignKey)
                .distinct()
                .filter(resourceId -> resourceRepository.findById(resourceId)
                        .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                        .isPresent())
                .collect(Collectors.toList());
    }

    /**
     * The {resource_id, user_id} pairs for which a secret must be provided
     * before the user can join the group: every group resource the user can
     * not access directly or through another group and has no secret for.
     */
    private List<Map<String, String>> computeMissingSecrets(String groupId, String userId) {
        List<Map<String, String>> missing = new ArrayList<>();
        for (String resourceId : getGroupResourceIds(groupId)) {
            if (userHasAccessElsewhere(resourceId, userId, groupId)) {
                continue;
            }
            if (secretRepository.findByResourceIdAndUserId(resourceId, userId).isPresent()) {
                continue;
            }
            Map<String, String> pair = new LinkedHashMap<>();
            pair.put("resource_id", resourceId);
            pair.put("user_id", userId);
            missing.add(pair);
        }
        return missing;
    }

    /**
     * Check whether a user can access a resource without going through the
     * excluded group: either a direct user permission or a permission of
     * another group the user belongs to.
     */
    private boolean userHasAccessElsewhere(String resourceId, String userId, String excludedGroupId) {
        if (permissionRepository.userHasAccess(resourceId, userId, Permission.READ)) {
            return true;
        }
        return groupUserRepository.findByUserId(userId).stream()
                .map(GroupUser::getGroupId)
                .filter(gid -> gid != null && !gid.equals(excludedGroupId))
                .anyMatch(gid -> permissionRepository.hasAccess(Permission.RESOURCE_ACO, resourceId,
                        Permission.GROUP_ARO, gid, Permission.READ));
    }

    private Optional<GroupDto.SecretData> findProvidedSecret(
            List<GroupDto.SecretData> providedSecrets, Map<String, String> pair) {
        return providedSecrets.stream()
                .filter(s -> pair.get("resource_id").equals(s.getResourceId())
                        && pair.get("user_id").equals(s.getUserId())
                        && s.getData() != null && !s.getData().isBlank())
                .findFirst();
    }

    private Set<String> splitCsv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Raised when the group is (still) sole owner of shared content. Carries
     * the blocking resources so the controller can build the 400
     * errors.resources.sole_owner body (rendered fields are simple columns,
     * safe to touch after rollback).
     */
    public static class GroupSoleOwnerConflictException extends RuntimeException {

        private final transient List<Resource> blockingResources;

        public GroupSoleOwnerConflictException(List<Resource> blockingResources) {
            super("The group cannot be deleted. The group should not be sole owner of shared content, "
                    + "transfer the ownership to other users.");
            this.blockingResources = blockingResources;
        }

        public List<Resource> getBlockingResources() {
            return blockingResources;
        }
    }
}
