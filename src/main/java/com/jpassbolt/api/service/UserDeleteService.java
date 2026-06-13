package com.jpassbolt.api.service;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.dto.UserDto;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * User deletion + dry-run shared logic, ported from the PHP reference
 * (UsersDeleteController + UsersTable::softDelete +
 * PermissionsFindersTrait::findSharedAcosByAroIsSoleOwner with
 * checkGroupsUsers=true).
 *
 * <p>
 * Sole-owner semantics (group dimension included, PHP checkGroupsUsers): the
 * blocking set is (resources only owned by the user + the groups he is sole
 * manager of) − (resources owned by the NON-EMPTY groups he is sole manager
 * of — fixed by transfer.managers, not by resource transfers) − (resources
 * only accessible by the user and his only-member groups — soft-deleted with
 * the user). Additionally, being sole manager of a non-empty group blocks
 * deletion (errors.groups.sole_manager) unless transfer.managers covers
 * every such group.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDeleteService {

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final SecretRepository secretRepository;
    private final ResourceRepository resourceRepository;
    private final GpgKeyRepository gpgKeyRepository;
    private final ProfileRepository profileRepository;
    private final GroupRepository groupRepository;
    private final GroupUserRepository groupUserRepository;
    private final FavoriteRepository favoriteRepository;
    private final SettingsProperties settingsProperties;

    /**
     * Deletion conflicts check shared by DELETE and dry-run (PHP
     * _validateDelete): sole manager of non-empty groups and sole owner of
     * shared content. Read-only — writes nothing. Throws
     * {@link UserDeleteConflictException} carrying the pre-rendered error
     * body (rendered inside the transaction so the controller never touches
     * lazy associations).
     */
    @Transactional(readOnly = true)
    public void validateDelete(String targetUserId) {
        List<String> soleManagerGroupIds = findNonEmptyGroupIdsWhereUserIsSoleManager(targetUserId);
        List<String> blockingResourceIds = findBlockingResourceIds(targetUserId);
        if (soleManagerGroupIds.isEmpty() && blockingResourceIds.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder("The user cannot be deleted.");
        Map<String, Object> errors = new LinkedHashMap<>();
        if (!soleManagerGroupIds.isEmpty()) {
            Map<String, Object> soleManager = new LinkedHashMap<>();
            soleManager.put("sole_manager", renderBlockingGroups(soleManagerGroupIds));
            errors.put("groups", soleManager);
            message.append(" The user should not be sole group manager of group(s), "
                    + "transfer the management to other users.");
        }
        if (!blockingResourceIds.isEmpty()) {
            Map<String, Object> soleOwner = new LinkedHashMap<>();
            soleOwner.put("sole_owner", renderBlockingResources(blockingResourceIds));
            errors.put("resources", soleOwner);
            message.append(" The user should not be sole owner of shared content, "
                    + "transfer the ownership to other users.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errors", errors);

        // PHP also reports the groups that would be deleted along the user.
        List<String> groupsToDelete = groupUserRepository.findGroupIdsWhereUserOnlyMember(targetUserId);
        if (!groupsToDelete.isEmpty()) {
            body.put("groups_to_delete", renderBlockingGroups(groupsToDelete));
        }
        throw new UserDeleteConflictException(message.toString(), body);
    }

    /**
     * Delete a user: optional manager/ownership transfers, conflict
     * validation, then the cascade — all in one transaction (any failure
     * rolls back, matching PHP's transactional closure).
     */
    @Transactional
    public void deleteUser(String targetUserId, String actorId, UserDto.DeleteRequest request) {
        User user = userRepository.findById(targetUserId)
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "The user does not exist or has been already deleted."));

        // PHP order: _transferGroupsManagers → _transferContentOwners →
        // _validateDelete.
        if (request != null && request.getTransfer() != null
                && request.getTransfer().getManagers() != null
                && !request.getTransfer().getManagers().isEmpty()) {
            applyManagerTransfers(targetUserId, request.getTransfer().getManagers());
        }
        if (request != null && request.getTransfer() != null
                && request.getTransfer().getOwners() != null
                && !request.getTransfer().getOwners().isEmpty()) {
            applyOwnershipTransfers(targetUserId, request.getTransfer().getOwners());
        }

        validateDelete(targetUserId);

        // Cascade (UsersTable::softDelete):
        // 1) resources only this user (and his only-member groups) can
        // access -> soft delete
        List<String> onlyMemberGroupIds = groupUserRepository
                .findGroupIdsWhereUserOnlyMember(targetUserId);
        List<String> onlyAccessibleAros = new ArrayList<>();
        onlyAccessibleAros.add(targetUserId);
        onlyAccessibleAros.addAll(onlyMemberGroupIds);
        List<String> privateResourceIds = permissionRepository
                .findResourceIdsOnlyAccessibleByAros(onlyAccessibleAros);
        for (String resourceId : privateResourceIds) {
            resourceRepository.findById(resourceId).ifPresent(resource -> {
                resource.setDeleted(true);
                resource.setModifiedBy(actorId);
                resourceRepository.save(resource);
            });
        }
        // 2) soft-delete the groups the user is the only member of and drop
        // their permissions (PHP "we do not want empty groups")
        for (String groupId : onlyMemberGroupIds) {
            groupRepository.findById(groupId)
                    .filter(g -> !Boolean.TRUE.equals(g.getDeleted()))
                    .ifPresent(g -> {
                        g.setDeleted(true);
                        g.setModifiedBy(actorId);
                        groupRepository.save(g);
                    });
            permissionRepository.deleteByAroAndAroForeignKey(Permission.GROUP_ARO, groupId);
        }
        // 3) hard-delete all of the user's group memberships
        groupUserRepository.deleteByUserId(targetUserId);
        // 4) hard-delete all of the user's permissions
        permissionRepository.deleteByAroAndAroForeignKey(Permission.USER_ARO, targetUserId);
        // 5) hard-delete all of the user's secrets
        secretRepository.deleteByUserId(targetUserId);
        // 6) hard-delete all of the user's favorites
        favoriteRepository.deleteByUserId(targetUserId);
        // 7) soft-delete the user's gpg keys
        gpgKeyRepository.findByUserId(targetUserId).forEach(key -> {
            key.setDeleted(true);
            gpgKeyRepository.save(key);
        });
        // 8) soft-delete the user (profile rows are kept, like PHP)
        user.setDeleted(true);
        userRepository.save(user);

        log.info("User {} deleted by {} ({} private resources soft-deleted, {} groups soft-deleted)",
                targetUserId, actorId, privateResourceIds.size(), onlyMemberGroupIds.size());
    }

    /**
     * Resources blocking the deletion — PHP
     * findSharedAcosByAroIsSoleOwner(Resource, user, checkGroupsUsers=true).
     */
    private List<String> findBlockingResourceIds(String targetUserId) {
        List<String> soleManagerGroupIds = groupUserRepository
                .findGroupIdsWhereUserIsSoleManager(targetUserId);
        List<String> onlyMemberGroupIds = groupUserRepository
                .findGroupIdsWhereUserOnlyMember(targetUserId);

        // ACOS only owned by the user + his sole-manager groups
        List<String> ownerAros = new ArrayList<>();
        ownerAros.add(targetUserId);
        ownerAros.addAll(soleManagerGroupIds);
        Set<String> blocking = new LinkedHashSet<>(
                permissionRepository.findResourceIdsWhereArosAreSoleOwner(ownerAros));

        // minus the ACOS owned by the NON-EMPTY groups he is sole manager of
        // (those conflicts are solved by transferring the group management)
        List<String> nonEmptySoleManagerGroups = new ArrayList<>(soleManagerGroupIds);
        nonEmptySoleManagerGroups.removeAll(onlyMemberGroupIds);
        if (!nonEmptySoleManagerGroups.isEmpty()) {
            blocking.removeAll(permissionRepository
                    .findResourceIdsOwnedByAros(nonEmptySoleManagerGroups));
        }

        // minus the ACOS only accessible by the user + his only-member groups
        List<String> onlyAccessibleAros = new ArrayList<>();
        onlyAccessibleAros.add(targetUserId);
        onlyAccessibleAros.addAll(onlyMemberGroupIds);
        blocking.removeAll(permissionRepository
                .findResourceIdsOnlyAccessibleByAros(onlyAccessibleAros));

        return new ArrayList<>(blocking);
    }

    /**
     * Non-empty groups whose only manager is the user — PHP
     * findNonEmptyGroupsWhereUserIsSoleManager (sole manager minus only
     * member).
     */
    private List<String> findNonEmptyGroupIdsWhereUserIsSoleManager(String targetUserId) {
        List<String> soleManager = new ArrayList<>(groupUserRepository
                .findGroupIdsWhereUserIsSoleManager(targetUserId));
        soleManager.removeAll(groupUserRepository.findGroupIdsWhereUserOnlyMember(targetUserId));
        return soleManager;
    }

    /**
     * PHP _transferGroupsManagers: the transferred group set must exactly
     * equal the blocking (non-empty sole-manager) group set; each referenced
     * groups_users row is then promoted to manager (is_admin=true), scoped to
     * the blocking groups.
     */
    private void applyManagerTransfers(String targetUserId, List<UserDto.TransferManager> managers) {
        TreeSet<String> blocking = new TreeSet<>(findNonEmptyGroupIdsWhereUserIsSoleManager(targetUserId));
        TreeSet<String> transferred = new TreeSet<>();
        for (UserDto.TransferManager manager : managers) {
            if (manager.getGroupId() != null) {
                transferred.add(manager.getGroupId());
            }
        }
        if (!blocking.equals(transferred)) {
            throw new IllegalArgumentException("The transfer is not authorized");
        }
        for (UserDto.TransferManager manager : managers) {
            GroupUser groupUser = groupUserRepository.findById(manager.getId())
                    .orElseThrow(() -> new IllegalArgumentException("The transfer is not authorized"));
            if (!blocking.contains(groupUser.getGroupId())
                    || !groupUser.getGroupId().equals(manager.getGroupId())
                    || targetUserId.equals(groupUser.getUserId())) {
                // The membership must belong to the declared blocking group
                // and must not be the deleted user's own membership.
                throw new IllegalArgumentException("The transfer is not authorized");
            }
            groupUser.setIsAdmin(true);
            groupUserRepository.save(groupUser);
        }
    }

    /**
     * The transferred resource set must exactly equal the blocking resource
     * set (PHP compares the sorted lists); each referenced permission is then
     * promoted to OWNER(15).
     */
    private void applyOwnershipTransfers(String targetUserId, List<UserDto.TransferOwner> owners) {
        List<String> blockingResourceIds = findBlockingResourceIds(targetUserId);

        TreeSet<String> blocking = new TreeSet<>(blockingResourceIds);
        TreeSet<String> transferred = new TreeSet<>();
        for (UserDto.TransferOwner owner : owners) {
            if (owner.getAcoForeignKey() != null) {
                transferred.add(owner.getAcoForeignKey());
            }
        }
        if (!blocking.equals(transferred)) {
            throw new IllegalArgumentException("The transfer is not authorized");
        }

        for (UserDto.TransferOwner owner : owners) {
            Permission permission = permissionRepository.findById(owner.getId())
                    .orElseThrow(() -> new IllegalArgumentException("The transfer is not authorized"));
            if (!permission.getAcoForeignKey().equals(owner.getAcoForeignKey())
                    || targetUserId.equals(permission.getAroForeignKey())) {
                // Permission must target the declared resource and must not
                // belong to the user being deleted.
                throw new IllegalArgumentException("The transfer is not authorized");
            }
            permission.setType(Permission.OWNER);
            permissionRepository.save(permission);
        }
    }

    /**
     * Render the blocking resources with their permissions (each permission
     * carrying its user + profile), shape of schemas/userDelete /
     * userDeleteDryRun.
     */
    private List<Map<String, Object>> renderBlockingResources(List<String> resourceIds) {
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (String resourceId : resourceIds) {
            Resource resource = resourceRepository.findById(resourceId).orElse(null);
            if (resource == null) {
                continue;
            }
            Map<String, Object> resourceMap = new LinkedHashMap<>();
            resourceMap.put("id", resource.getId());
            resourceMap.put("name", resource.getName());
            resourceMap.put("username", resource.getUsername());
            resourceMap.put("uri", resource.getUri());
            resourceMap.put("deleted", resource.getDeleted());
            resourceMap.put("created", resource.getCreated());
            resourceMap.put("modified", resource.getModified());
            resourceMap.put("created_by", resource.getCreatedBy());
            resourceMap.put("modified_by", resource.getModifiedBy());
            resourceMap.put("resource_type_id", resource.getResourceTypeId());

            List<Map<String, Object>> permissions = new ArrayList<>();
            for (Permission permission : permissionRepository.findByResourceId(resourceId)) {
                Map<String, Object> permissionMap = new LinkedHashMap<>();
                permissionMap.put("id", permission.getId());
                permissionMap.put("aco", permission.getAco());
                permissionMap.put("aco_foreign_key", permission.getAcoForeignKey());
                permissionMap.put("aro", permission.getAro());
                permissionMap.put("aro_foreign_key", permission.getAroForeignKey());
                permissionMap.put("type", permission.getType());
                permissionMap.put("created", permission.getCreated());
                permissionMap.put("modified", permission.getModified());
                if (Permission.USER_ARO.equals(permission.getAro())) {
                    permissionMap.put("user", userRepository.findById(permission.getAroForeignKey())
                            .map(this::renderUser).orElse(null));
                } else if (Permission.GROUP_ARO.equals(permission.getAro())) {
                    // PHP contain permissions.group
                    permissionMap.put("group", groupRepository.findById(permission.getAroForeignKey())
                            .map(this::renderGroup).orElse(null));
                }
                permissions.add(permissionMap);
            }
            resourceMap.put("permissions", permissions);
            rendered.add(resourceMap);
        }
        return rendered;
    }

    /**
     * Render groups with their memberships (each carrying its user +
     * profile), shape of the PHP errors.groups.sole_manager /
     * groups_to_delete lists (contain groups_users.user.profile).
     */
    private List<Map<String, Object>> renderBlockingGroups(List<String> groupIds) {
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (String groupId : groupIds) {
            Group group = groupRepository.findById(groupId).orElse(null);
            if (group == null) {
                continue;
            }
            Map<String, Object> groupMap = renderGroup(group);
            List<Map<String, Object>> groupsUsers = new ArrayList<>();
            for (GroupUser groupUser : groupUserRepository.findByGroupId(groupId)) {
                Map<String, Object> guMap = new LinkedHashMap<>();
                guMap.put("id", groupUser.getId());
                guMap.put("group_id", groupUser.getGroupId());
                guMap.put("user_id", groupUser.getUserId());
                guMap.put("is_admin", groupUser.getIsAdmin());
                guMap.put("created", groupUser.getCreated());
                guMap.put("user", userRepository.findById(groupUser.getUserId())
                        .map(this::renderUser).orElse(null));
                groupsUsers.add(guMap);
            }
            groupMap.put("groups_users", groupsUsers);
            rendered.add(groupMap);
        }
        return rendered;
    }

    private Map<String, Object> renderGroup(Group group) {
        Map<String, Object> groupMap = new LinkedHashMap<>();
        groupMap.put("id", group.getId());
        groupMap.put("name", group.getName());
        groupMap.put("deleted", group.getDeleted());
        groupMap.put("created", group.getCreated());
        groupMap.put("modified", group.getModified());
        groupMap.put("created_by", group.getCreatedBy());
        groupMap.put("modified_by", group.getModifiedBy());
        return groupMap;
    }

    private Map<String, Object> renderUser(User user) {
        Map<String, Object> userMap = new LinkedHashMap<>();
        userMap.put("id", user.getId());
        userMap.put("role_id", user.getRoleId());
        userMap.put("username", user.getUsername());
        userMap.put("active", user.getActive());
        userMap.put("deleted", user.getDeleted());
        userMap.put("created", user.getCreated());
        userMap.put("modified", user.getModified());
        userMap.put("profile", profileRepository.findByUserId(user.getId())
                .map(this::renderProfile).orElse(null));
        return userMap;
    }

    private Map<String, Object> renderProfile(Profile profile) {
        Map<String, Object> profileMap = new LinkedHashMap<>();
        profileMap.put("id", profile.getId());
        profileMap.put("user_id", profile.getUserId());
        profileMap.put("first_name", profile.getFirstName());
        profileMap.put("last_name", profile.getLastName());
        profileMap.put("created", profile.getCreated());
        profileMap.put("modified", profile.getModified());
        String base = settingsProperties.getFullBaseUrl();
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("medium", base + "/img/avatar/user_medium.png");
        url.put("small", base + "/img/avatar/user.png");
        Map<String, Object> avatar = new LinkedHashMap<>();
        avatar.put("url", url);
        profileMap.put("avatar", avatar);
        return profileMap;
    }

    /**
     * Raised when the user is sole owner of shared content. Carries the
     * pre-rendered {errors: {resources: {sole_owner: [...]}}} body so the
     * controller can build the 400 envelope without re-entering the
     * (rolled-back) transaction.
     */
    public static class UserDeleteConflictException extends RuntimeException {

        private final transient Map<String, Object> body;

        public UserDeleteConflictException(String message, Map<String, Object> body) {
            super(message);
            this.body = body;
        }

        public Map<String, Object> getBody() {
            return body;
        }
    }
}
