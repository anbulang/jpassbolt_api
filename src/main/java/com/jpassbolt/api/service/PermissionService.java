package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.ShareDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.exception.ShareValidationException;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing resource permissions and sharing.
 *
 * <p>
 * The share / dry-run logic mirrors the PHP reference implementation
 * (ResourcesShareService + PermissionsUpdatePermissionsService): changes are
 * first applied to an in-memory snapshot of the current permissions (shared
 * validation path for both the real share and the dry run), the
 * before/after sets of users having access are diffed
 * (PermissionsGetUsersIdsHavingAccessToService, including group fan-out via
 * groups_users), and only the real share persists the change set.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private static final String VALIDATION_MESSAGE = "Could not validate resource data.";

    private final PermissionRepository permissionRepository;
    private final ResourceRepository resourceRepository;
    private final SecretRepository secretRepository;
    private final FavoriteRepository favoriteRepository;
    private final GroupUserRepository groupUserRepository;

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
     * Check if a user has at least the specified permission level on a resource
     * (direct User permission rows only).
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
     * Check if a user has at least the specified permission level on a
     * resource, either directly or inherited through one of their groups
     * (PHP PermissionsTable::hasAccess semantics).
     */
    @Transactional(readOnly = true)
    public boolean hasAccessIncludingGroups(String resourceId, String userId, int minType) {
        if (permissionRepository.userHasAccess(resourceId, userId, minType)) {
            return true;
        }
        Set<String> groupIds = new HashSet<>();
        for (GroupUser membership : groupUserRepository.findByUserId(userId)) {
            groupIds.add(membership.getGroupId());
        }
        if (groupIds.isEmpty()) {
            return false;
        }
        return permissionRepository.findByAcoForeignKeyAndAro(resourceId, Permission.GROUP_ARO).stream()
                .anyMatch(p -> groupIds.contains(p.getAroForeignKey()) && p.getType() >= minType);
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
     * All user ids currently having access to a resource: direct "User"
     * permission rows united with "Group" rows fanned out through
     * groups_users (PHP PermissionsGetUsersIdsHavingAccessToService).
     */
    @Transactional(readOnly = true)
    public Set<String> getUsersIdsHavingAccessTo(String resourceId) {
        return usersIdsHavingAccess(toSimPerms(permissionRepository.findByResourceId(resourceId)));
    }

    /**
     * Simulate a share operation (dry run). Applies the requested permission
     * changes to an in-memory snapshot (full validation, identical to the
     * real share including the at-least-one-owner rule) and returns the user
     * ids that would gain ("added") or lose ("deleted") access. Never
     * persists anything.
     *
     * @param resourceId        the resource ID
     * @param userId            the requesting user's ID
     * @param permissionChanges list of permission change requests
     * @return map with "added" and "deleted" user ID lists
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> shareDryRun(String resourceId, String userId,
            List<ShareDto.PermissionChange> permissionChanges) {
        validateShareRequest(resourceId, userId);

        List<Permission> current = permissionRepository.findByResourceId(resourceId);
        ChangeSet changeSet = computeChanges(current, permissionChanges);

        Set<String> before = usersIdsHavingAccess(toSimPerms(current));
        Set<String> after = usersIdsHavingAccess(changeSet.result());

        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("added", new ArrayList<>(difference(after, before)));
        result.put("deleted", new ArrayList<>(difference(before, after)));
        return result;
    }

    /**
     * Share a resource by updating permissions and secrets
     * (PHP ResourcesShareService::share, transactional).
     *
     * <p>
     * Row semantics: id present → update type (or physical delete when
     * delete=true); no id + delete=true → locate by (aco_foreign_key,
     * aro_foreign_key) (the plugin also sends this id-less deleteUser shape);
     * no id → create. Unknown id → per-row validation error. After applying,
     * at least one OWNER must remain, and the provided secrets must cover
     * exactly the set of users who newly gained access. Users who lost access
     * get their secrets and favorites hard-deleted in the same transaction.
     * </p>
     *
     * @param resourceId        the resource ID
     * @param userId            the requesting user's ID
     * @param permissionChanges list of permission change requests
     * @param secrets           secrets for the users who gain access
     */
    @Transactional
    public void share(String resourceId, String userId,
            List<ShareDto.PermissionChange> permissionChanges,
            List<ShareDto.SecretAdd> secrets) {
        validateShareRequest(resourceId, userId);

        List<Permission> current = permissionRepository.findByResourceId(resourceId);
        ChangeSet changeSet = computeChanges(current, permissionChanges);

        Set<String> before = usersIdsHavingAccess(toSimPerms(current));
        Set<String> after = usersIdsHavingAccess(changeSet.result());
        Set<String> added = difference(after, before);
        Set<String> removed = difference(before, after);

        validateSecretsCoverage(added, secrets);

        // --- Persist the validated change set ---
        for (String permissionId : changeSet.deletedIds()) {
            permissionRepository.deleteById(permissionId);
            log.info("Removed permission {} on resource {}", permissionId, resourceId);
        }
        for (Map.Entry<String, Integer> update : changeSet.updatedTypes().entrySet()) {
            permissionRepository.findById(update.getKey()).ifPresent(perm -> {
                perm.setType(update.getValue());
                permissionRepository.save(perm);
                log.info("Updated permission {} on resource {} to type {}",
                        perm.getId(), resourceId, update.getValue());
            });
        }
        for (SimPerm create : changeSet.created()) {
            Permission perm = new Permission();
            perm.setAco(Permission.RESOURCE_ACO);
            perm.setAcoForeignKey(resourceId);
            perm.setAro(create.aro);
            perm.setAroForeignKey(create.aroForeignKey);
            perm.setType(create.type);
            permissionRepository.save(perm);
            log.info("Created permission for {} {} on resource {} with type {}",
                    create.aro, create.aroForeignKey, resourceId, create.type);
        }

        // --- Secrets for the users who gained access ---
        if (secrets != null) {
            for (ShareDto.SecretAdd secretAdd : secrets) {
                if (secretAdd.getUserId() == null || secretAdd.getData() == null) {
                    continue; // already validated by validateSecretsCoverage
                }
                if (secretRepository.findByResourceIdAndUserId(resourceId, secretAdd.getUserId()).isEmpty()) {
                    Secret secret = new Secret();
                    secret.setResourceId(resourceId);
                    secret.setUserId(secretAdd.getUserId());
                    secret.setData(secretAdd.getData());
                    secretRepository.save(secret);
                    log.info("Created secret for user {} on resource {}", secretAdd.getUserId(), resourceId);
                }
            }
        }

        // --- Cascade for users who lost access (no remaining permission
        // path, group fan-out included): hard-delete their secrets and
        // favorites (PHP ResourcesTable::deleteLostAccessSecrets /
        // deleteLostAccessFavorites). ---
        for (String lostUserId : removed) {
            secretRepository.findByResourceIdAndUserId(resourceId, lostUserId)
                    .ifPresent(secretRepository::delete);
            favoriteRepository.findByUserIdAndForeignKey(lostUserId, resourceId)
                    .ifPresent(favoriteRepository::delete);
            log.info("Revoked access cleanup for user {} on resource {}", lostUserId, resourceId);
        }
    }

    // ------------------------------------------------------------------
    // Validation / simulation internals (single shared path for share and
    // dry run)
    // ------------------------------------------------------------------

    /**
     * Validate that the resource exists (404, NOT 400 — and never 403 for a
     * missing/soft-deleted resource, to avoid leaking existence) and that the
     * requesting user is an OWNER (403).
     */
    private void validateShareRequest(String resourceId, String userId) {
        boolean exists = resourceRepository.findById(resourceId)
                .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                .isPresent();
        if (!exists) {
            throw new PassboltApiException(HttpStatus.NOT_FOUND, "The resource does not exist.");
        }
        if (!hasAccessIncludingGroups(resourceId, userId, Permission.OWNER)) {
            throw new SecurityException("You are not authorized to share this resource.");
        }
    }

    /**
     * Apply the requested changes to an in-memory copy of the current
     * permissions. Collects per-row validation errors (PHP
     * CustomValidationException shape) and enforces the at-least-one-owner
     * rule. Never touches managed entities or the database.
     */
    private ChangeSet computeChanges(List<Permission> current, List<ShareDto.PermissionChange> changes) {
        List<SimPerm> sim = toSimPerms(current);
        List<String> deletedIds = new ArrayList<>();
        Map<String, Integer> updatedTypes = new LinkedHashMap<>();
        List<SimPerm> created = new ArrayList<>();
        Map<String, Object> rowErrors = new LinkedHashMap<>();

        List<ShareDto.PermissionChange> safeChanges = changes != null ? changes : List.of();
        for (int i = 0; i < safeChanges.size(); i++) {
            ShareDto.PermissionChange change = safeChanges.get(i);
            boolean isDelete = Boolean.TRUE.equals(change.getDelete());

            if (change.getId() != null) {
                // Locate by id within this ACO (PHP getPermission semantics,
                // cross-resource ids are rejected as non-existent).
                Optional<SimPerm> found = sim.stream()
                        .filter(p -> change.getId().equals(p.id))
                        .findFirst();
                if (found.isEmpty()) {
                    rowErrors.put(String.valueOf(i),
                            Map.of("id", Map.of("exists", "The permission does not exist.")));
                    continue;
                }
                SimPerm target = found.get();
                if (isDelete) {
                    sim.remove(target);
                    deletedIds.add(target.id);
                } else {
                    Integer type = change.getType();
                    if (type == null || !Permission.isValidType(type)) {
                        rowErrors.put(String.valueOf(i), invalidTypeError());
                        continue;
                    }
                    if (target.type != type) {
                        target.type = type;
                        updatedTypes.put(target.id, type);
                    }
                }
            } else if (isDelete) {
                // Id-less deleteUser shape: locate by aro_foreign_key
                // (lenient — a missing row is simply skipped, matching the
                // previous Java behaviour the plugin tolerates).
                sim.stream()
                        .filter(p -> Objects.equals(p.aroForeignKey, change.getAroForeignKey()))
                        .findFirst()
                        .ifPresent(p -> {
                            sim.remove(p);
                            if (p.id != null) {
                                deletedIds.add(p.id);
                            } else {
                                created.remove(p); // created earlier in this very change set
                            }
                        });
            } else {
                // Create a new permission row.
                String aro = change.getAro() != null ? change.getAro() : Permission.USER_ARO;
                String aroForeignKey = change.getAroForeignKey();
                Integer type = change.getType();
                if (aroForeignKey == null) {
                    rowErrors.put(String.valueOf(i), Map.of("aro_foreign_key",
                            Map.of("_required", "The aro_foreign_key is required.")));
                    continue;
                }
                if (type == null || !Permission.isValidType(type)) {
                    rowErrors.put(String.valueOf(i), invalidTypeError());
                    continue;
                }
                // Lenient upsert: PHP raises a uniqueness validation error
                // when an id-less add targets an ARO that already has a row;
                // the official plugin never sends that shape, and updating
                // the existing row keeps backward compatibility with the
                // previous Java behaviour (no unique-constraint blowup).
                Optional<SimPerm> existing = sim.stream()
                        .filter(p -> aroForeignKey.equals(p.aroForeignKey) && aro.equals(p.aro))
                        .findFirst();
                if (existing.isPresent()) {
                    SimPerm target = existing.get();
                    if (target.type != type) {
                        target.type = type;
                        if (target.id != null) {
                            updatedTypes.put(target.id, type);
                        }
                        // id == null: the row was created earlier in this very
                        // change set — the shared SimPerm reference in
                        // "created" already carries the new type.
                    }
                } else {
                    SimPerm fresh = new SimPerm(null, aro, aroForeignKey, type);
                    sim.add(fresh);
                    created.add(fresh);
                }
            }
        }

        if (!rowErrors.isEmpty()) {
            Map<String, Object> errors = new LinkedHashMap<>();
            errors.put("permissions", rowErrors);
            throw new ShareValidationException(VALIDATION_MESSAGE, errors);
        }

        boolean hasOwner = sim.stream().anyMatch(p -> p.type == Permission.OWNER);
        if (!hasOwner) {
            Map<String, Object> errors = new LinkedHashMap<>();
            errors.put("permissions", Map.of("at_least_one_owner",
                    "At least one owner permission must be provided."));
            throw new ShareValidationException(VALIDATION_MESSAGE, errors);
        }

        return new ChangeSet(sim, deletedIds, updatedTypes, created);
    }

    /**
     * The provided secrets must cover EXACTLY the users who newly gained
     * access — a missing or an extra secret is a validation failure (the
     * plugin encrypts for precisely the dry-run "added" set; a lax check
     * here would silently swallow client-side encryption gaps).
     */
    private void validateSecretsCoverage(Set<String> addedUserIds, List<ShareDto.SecretAdd> secrets) {
        Map<String, Object> secretErrors = new LinkedHashMap<>();
        Set<String> providedUserIds = new LinkedHashSet<>();
        if (secrets != null) {
            for (ShareDto.SecretAdd secretAdd : secrets) {
                if (secretAdd.getUserId() == null || secretAdd.getData() == null
                        || secretAdd.getData().isEmpty()) {
                    secretErrors.put(String.valueOf(secrets.indexOf(secretAdd)),
                            Map.of("data", Map.of("_required", "A secret with user_id and data is required.")));
                    continue;
                }
                providedUserIds.add(secretAdd.getUserId());
            }
        }
        for (String missing : difference(addedUserIds, providedUserIds)) {
            secretErrors.put(missing, Map.of("_required",
                    "A secret is required for each user who gains access to the resource."));
        }
        for (String extra : difference(providedUserIds, addedUserIds)) {
            secretErrors.put(extra, Map.of("not_allowed",
                    "The secret does not match a user who gains access to the resource."));
        }
        if (!secretErrors.isEmpty()) {
            Map<String, Object> errors = new LinkedHashMap<>();
            errors.put("secrets", secretErrors);
            throw new ShareValidationException(VALIDATION_MESSAGE, errors);
        }
    }

    /**
     * User ids having access through the given (real or simulated) permission
     * rows: direct User rows ∪ Group rows fanned out via groups_users.
     */
    private Set<String> usersIdsHavingAccess(List<SimPerm> permissions) {
        Set<String> userIds = new LinkedHashSet<>();
        for (SimPerm perm : permissions) {
            if (Permission.USER_ARO.equals(perm.aro)) {
                userIds.add(perm.aroForeignKey);
            } else if (Permission.GROUP_ARO.equals(perm.aro)) {
                for (GroupUser membership : groupUserRepository.findByGroupId(perm.aroForeignKey)) {
                    if (membership.getUserId() != null) {
                        userIds.add(membership.getUserId());
                    }
                }
            }
        }
        return userIds;
    }

    private static Map<String, Object> invalidTypeError() {
        return Map.of("type", Map.of("inList",
                "The type must be one of the following: 1, 7, 15."));
    }

    private static List<SimPerm> toSimPerms(List<Permission> permissions) {
        List<SimPerm> sim = new ArrayList<>();
        for (Permission p : permissions) {
            sim.add(new SimPerm(p.getId(), p.getAro(), p.getAroForeignKey(), p.getType()));
        }
        return sim;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    /**
     * Lightweight in-memory copy of a permission row, so the simulation never
     * mutates managed entities (a dirty-checked type change would otherwise
     * leak to the database on flush).
     */
    private static final class SimPerm {
        private final String id; // null for rows created by this change set
        private final String aro;
        private final String aroForeignKey;
        private int type;

        private SimPerm(String id, String aro, String aroForeignKey, int type) {
            this.id = id;
            this.aro = aro;
            this.aroForeignKey = aroForeignKey;
            this.type = type;
        }
    }

    /**
     * Outcome of applying a change list to the in-memory snapshot.
     */
    private record ChangeSet(List<SimPerm> result, List<String> deletedIds,
            Map<String, Integer> updatedTypes, List<SimPerm> created) {
    }
}
