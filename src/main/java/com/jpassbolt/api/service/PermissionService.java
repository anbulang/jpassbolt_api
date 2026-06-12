package com.jpassbolt.api.service;

import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing resource permissions and sharing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionRepository permissionRepository;
    private final ResourceRepository resourceRepository;
    private final SecretRepository secretRepository;
    private final FavoriteRepository favoriteRepository;

    /**
     * Get all permissions for a resource.
     *
     * @param resourceId the resource ID
     * @return list of permissions
     */
    @Transactional(readOnly = true)
    public List<Permission> getResourcePermissions(String resourceId) {
        return permissionRepository.findByResourceId(resourceId);
    }

    /**
     * Check if a user has at least the specified permission level on a resource.
     *
     * @param resourceId the resource ID
     * @param userId     the user ID
     * @param minType    minimum permission type required
     * @return true if the user has sufficient access
     */
    @Transactional(readOnly = true)
    public boolean hasAccess(String resourceId, String userId, int minType) {
        return permissionRepository.userHasAccess(resourceId, userId, minType);
    }

    /**
     * Create the initial OWNER permission for a newly created resource.
     *
     * @param resourceId the resource ID
     * @param userId     the user ID (creator)
     * @return the created permission
     */
    @Transactional
    public Permission createOwnerPermission(String resourceId, String userId) {
        Permission permission = new Permission();
        permission.setAco(Permission.RESOURCE_ACO);
        permission.setAcoForeignKey(resourceId);
        permission.setAro(Permission.USER_ARO);
        permission.setAroForeignKey(userId);
        permission.setType(Permission.OWNER);
        return permissionRepository.save(permission);
    }

    /**
     * Simulate a share operation (dry run).
     * Returns the list of users that would need new secrets (added)
     * and users whose secrets would be removed (deleted).
     *
     * @param resourceId        the resource ID
     * @param userId            the requesting user's ID
     * @param permissionChanges list of permission change requests
     * @return map with "added" and "deleted" user ID lists
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> shareDryRun(String resourceId, String userId,
            List<Map<String, Object>> permissionChanges) {
        validateShareRequest(resourceId, userId);

        List<Permission> currentPermissions = permissionRepository.findByResourceId(resourceId);
        Set<String> currentUserIds = currentPermissions.stream()
                .filter(p -> Permission.USER_ARO.equals(p.getAro()))
                .map(Permission::getAroForeignKey)
                .collect(Collectors.toSet());

        Set<String> newUserIds = new HashSet<>();
        Set<String> removedUserIds = new HashSet<>();

        for (Map<String, Object> change : permissionChanges) {
            String aroForeignKey = (String) change.get("aro_foreign_key");
            Object typeObj = change.get("type");
            boolean isDelete = change.containsKey("delete") && Boolean.TRUE.equals(change.get("delete"));

            if (isDelete) {
                if (currentUserIds.contains(aroForeignKey)) {
                    removedUserIds.add(aroForeignKey);
                }
            } else if (!currentUserIds.contains(aroForeignKey)) {
                newUserIds.add(aroForeignKey);
            }
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("added", new ArrayList<>(newUserIds));
        result.put("deleted", new ArrayList<>(removedUserIds));
        return result;
    }

    /**
     * Share a resource by updating permissions and secrets.
     *
     * @param resourceId        the resource ID
     * @param userId            the requesting user's ID
     * @param permissionChanges list of permission change requests
     * @param secrets           list of secrets for newly added users
     */
    @Transactional
    public void share(String resourceId, String userId,
            List<Map<String, Object>> permissionChanges,
            List<Map<String, String>> secrets) {
        validateShareRequest(resourceId, userId);

        // ARO user ids whose permission gets deleted in this call — needed
        // afterwards to clean up favorites of users who lost access
        // (PHP ResourcesTable::deleteLostAccessFavorites).
        Set<String> removedAroUserIds = new HashSet<>();

        for (Map<String, Object> change : permissionChanges) {
            String aroForeignKey = (String) change.get("aro_foreign_key");
            String aro = (String) change.getOrDefault("aro", Permission.USER_ARO);
            boolean isDelete = change.containsKey("delete") && Boolean.TRUE.equals(change.get("delete"));

            if (isDelete) {
                removedAroUserIds.add(aroForeignKey);
                // Remove permission
                permissionRepository.findByAcoForeignKeyAndAroForeignKey(resourceId, aroForeignKey)
                        .ifPresent(permissionRepository::delete);
                // Remove associated secret
                secretRepository.findByResourceIdAndUserId(resourceId, aroForeignKey)
                        .ifPresent(secretRepository::delete);
                log.info("Removed permission for user {} on resource {}", aroForeignKey, resourceId);
            } else {
                // Add or update permission
                int type = parsePermissionType(change.get("type"));
                if (!Permission.isValidType(type)) {
                    throw new IllegalArgumentException("Invalid permission type: " + type);
                }

                Optional<Permission> existing = permissionRepository
                        .findByAcoForeignKeyAndAroForeignKey(resourceId, aroForeignKey);

                if (existing.isPresent()) {
                    // Update existing permission
                    Permission perm = existing.get();
                    perm.setType(type);
                    permissionRepository.save(perm);
                    log.info("Updated permission for user {} on resource {} to type {}",
                            aroForeignKey, resourceId, type);
                } else {
                    // Create new permission
                    Permission perm = new Permission();
                    perm.setAco(Permission.RESOURCE_ACO);
                    perm.setAcoForeignKey(resourceId);
                    perm.setAro(aro);
                    perm.setAroForeignKey(aroForeignKey);
                    perm.setType(type);
                    permissionRepository.save(perm);
                    log.info("Created permission for user {} on resource {} with type {}",
                            aroForeignKey, resourceId, type);
                }
            }
        }

        // Save secrets for newly added users
        if (secrets != null) {
            for (Map<String, String> secretData : secrets) {
                String secretUserId = secretData.get("user_id");
                String data = secretData.get("data");
                if (secretUserId != null && data != null) {
                    // Only create if no secret exists yet
                    if (secretRepository.findByResourceIdAndUserId(resourceId, secretUserId).isEmpty()) {
                        Secret secret = new Secret();
                        secret.setResourceId(resourceId);
                        secret.setUserId(secretUserId);
                        secret.setData(data);
                        secretRepository.save(secret);
                        log.info("Created secret for user {} on resource {}", secretUserId, resourceId);
                    }
                }
            }
        }

        // Validate at least one OWNER remains
        List<Permission> remaining = permissionRepository.findByResourceId(resourceId);
        boolean hasOwner = remaining.stream()
                .anyMatch(p -> p.getType() == Permission.OWNER);
        if (!hasOwner) {
            throw new IllegalStateException("Cannot remove all owners from a resource.");
        }

        // Cascade: hard-delete favorites of users who lost access through this
        // share operation (PHP ResourcesTable::deleteLostAccessFavorites).
        for (String affectedUserId : removedAroUserIds) {
            if (!permissionRepository.userHasAccess(resourceId, affectedUserId, Permission.READ)) {
                favoriteRepository.findByUserIdAndForeignKey(affectedUserId, resourceId)
                        .ifPresent(favoriteRepository::delete);
            }
        }
    }

    /**
     * Validate that the requesting user has OWNER permission on the resource.
     */
    private void validateShareRequest(String resourceId, String userId) {
        // Resource must exist and not be deleted
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found: " + resourceId));
        if (Boolean.TRUE.equals(resource.getDeleted())) {
            throw new IllegalArgumentException("Resource is deleted: " + resourceId);
        }

        // User must be an OWNER
        if (!permissionRepository.userHasAccess(resourceId, userId, Permission.OWNER)) {
            throw new SecurityException("User is not authorized to share this resource.");
        }
    }

    /**
     * Parse permission type from various input formats (Integer, String, etc.)
     */
    private int parsePermissionType(Object typeObj) {
        if (typeObj instanceof Integer) {
            return (Integer) typeObj;
        } else if (typeObj instanceof String) {
            return Integer.parseInt((String) typeObj);
        } else if (typeObj instanceof Number) {
            return ((Number) typeObj).intValue();
        }
        throw new IllegalArgumentException("Invalid permission type format");
    }
}
