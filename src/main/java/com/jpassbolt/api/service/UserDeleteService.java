package com.jpassbolt.api.service;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.dto.UserDto;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GpgKeyRepository;
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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * User deletion + dry-run shared logic, ported from the PHP reference
 * (UsersDeleteController + UsersTable::softDelete +
 * PermissionsFindersTrait::findSharedAcosByAroIsSoleOwner — simplified to
 * the User ARO only, Groups are not implemented yet).
 *
 * <p>
 * Sole-owner semantics (no Group dimension): a resource blocks deletion when
 * the user holds an OWNER permission on it AND it has exactly one OWNER
 * permission AND more than one permission in total (i.e. it is shared).
 * Resources only the user can access do not block — they are soft-deleted
 * along with the user.
 * </p>
 *
 * <p>
 * TODO(groups-crud): once Groups land, re-add the group dimension to the
 * sole-owner computation (sole_manager error list, groups_to_delete,
 * permissions held through groups) and clean groups_users on delete.
 * TODO(favorites): PHP also deletes the user's favorites
 * (UsersTable::softDelete L482-507) — re-add when wiring that cluster.
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
    private final SettingsProperties settingsProperties;

    /**
     * Sole-owner check shared by DELETE and dry-run. Read-only — writes
     * nothing. Throws {@link UserDeleteConflictException} carrying the
     * pre-rendered error body (rendered inside the transaction so the
     * controller never touches lazy associations).
     */
    @Transactional(readOnly = true)
    public void validateDelete(String targetUserId) {
        List<String> blockingResourceIds = permissionRepository
                .findSharedResourceIdsWhereUserIsSoleOwner(targetUserId);
        if (!blockingResourceIds.isEmpty()) {
            Map<String, Object> soleOwner = new LinkedHashMap<>();
            soleOwner.put("sole_owner", renderBlockingResources(blockingResourceIds));
            Map<String, Object> errors = new LinkedHashMap<>();
            errors.put("resources", soleOwner);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("errors", errors);
            throw new UserDeleteConflictException(
                    "The user cannot be deleted. The user should not be sole owner of shared content, "
                            + "transfer the ownership to other users.",
                    body);
        }
    }

    /**
     * Delete a user: optional ownership transfer, sole-owner validation,
     * then the cascade — all in one transaction (any failure rolls back,
     * matching PHP's transactional closure).
     */
    @Transactional
    public void deleteUser(String targetUserId, String actorId, UserDto.DeleteRequest request) {
        User user = userRepository.findById(targetUserId)
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "The user does not exist or has been already deleted."));

        if (request != null && request.getTransfer() != null
                && request.getTransfer().getOwners() != null
                && !request.getTransfer().getOwners().isEmpty()) {
            applyOwnershipTransfers(targetUserId, request.getTransfer().getOwners());
        }
        // TODO(groups-crud): transfer.managers is accepted but ignored until
        // Groups are implemented.

        validateDelete(targetUserId);

        // Cascade (UsersTable::softDelete):
        // 1) resources only this user can access -> soft delete
        List<String> privateResourceIds = permissionRepository
                .findResourceIdsOnlyAccessibleByUser(targetUserId);
        for (String resourceId : privateResourceIds) {
            resourceRepository.findById(resourceId).ifPresent(resource -> {
                resource.setDeleted(true);
                resource.setModifiedBy(actorId);
                resourceRepository.save(resource);
            });
        }
        // 2) hard-delete all of the user's permissions
        permissionRepository.deleteByAroAndAroForeignKey(Permission.USER_ARO, targetUserId);
        // 3) hard-delete all of the user's secrets
        secretRepository.deleteByUserId(targetUserId);
        // 4) soft-delete the user's gpg keys
        gpgKeyRepository.findByUserId(targetUserId).forEach(key -> {
            key.setDeleted(true);
            gpgKeyRepository.save(key);
        });
        // 5) soft-delete the user (profile rows are kept, like PHP)
        user.setDeleted(true);
        userRepository.save(user);

        log.info("User {} deleted by {} ({} private resources soft-deleted)",
                targetUserId, actorId, privateResourceIds.size());
    }

    /**
     * The transferred resource set must exactly equal the blocking resource
     * set (PHP compares the sorted lists); each referenced permission is then
     * promoted to OWNER(15).
     */
    private void applyOwnershipTransfers(String targetUserId, List<UserDto.TransferOwner> owners) {
        List<String> blockingResourceIds = permissionRepository
                .findSharedResourceIdsWhereUserIsSoleOwner(targetUserId);

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
                }
                permissions.add(permissionMap);
            }
            resourceMap.put("permissions", permissions);
            rendered.add(resourceMap);
        }
        return rendered;
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
