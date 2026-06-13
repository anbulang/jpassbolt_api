package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.ShareDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.exception.ShareValidationException;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final PermissionRepository permissionRepository;
    private final ResourceRepository resourceRepository;
    private final SecretRepository secretRepository;
    private final FavoriteRepository favoriteRepository;
    private final GroupUserRepository groupUserRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;

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
     * (PHP PermissionsTable::hasAccess semantics). Delegates to a single
     * JPQL query (behaviour unchanged, eliminates the former N+1 fan-out).
     */
    @Transactional(readOnly = true)
    public boolean hasAccessIncludingGroups(String resourceId, String userId, int minType) {
        return permissionRepository.userHasAccessIncludingGroups(resourceId, userId, minType);
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
     * aro_foreign_key [+ aro when provided]) (the plugin also sends this
     * id-less deleteUser shape); no id → create (User or Group ARO — a Group
     * row fans access out to every group member via groups_users). Unknown id
     * → per-row validation error. After applying, at least one OWNER must
     * remain, every user who newly gained access must have a provided secret,
     * and every provided secret must target a user having access after the
     * change (re-submitting a ciphertext for a user keeping access updates
     * it — PHP SecretsUpdateSecretsService). Users without any remaining
     * access path (direct or through a group) get their secrets and
     * favorites hard-deleted in the same transaction.
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

        validateSecretsCoverage(added, after, secrets);

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

        // --- Secrets: create for users gaining access, update the ciphertext
        // when a row already exists (PHP SecretsUpdateSecretsService::
        // updateSecret is an update, NOT a skip — re-encrypting for users who
        // keep access is a legal update). Existing rows are batch-indexed in
        // one query. ---
        if (secrets != null && !secrets.isEmpty()) {
            Set<String> providedUserIds = new LinkedHashSet<>();
            for (ShareDto.SecretAdd secretAdd : secrets) {
                if (secretAdd.getUserId() != null && secretAdd.getData() != null) {
                    providedUserIds.add(secretAdd.getUserId());
                }
            }
            Map<String, Secret> existingByUserId = new LinkedHashMap<>();
            if (!providedUserIds.isEmpty()) {
                for (Secret existing : secretRepository
                        .findByResourceIdAndUserIdIn(resourceId, providedUserIds)) {
                    existingByUserId.put(existing.getUserId(), existing);
                }
            }
            for (ShareDto.SecretAdd secretAdd : secrets) {
                if (secretAdd.getUserId() == null || secretAdd.getData() == null) {
                    continue; // already validated by validateSecretsCoverage
                }
                Secret existing = existingByUserId.get(secretAdd.getUserId());
                if (existing != null) {
                    existing.setData(secretAdd.getData());
                    secretRepository.save(existing);
                    log.info("Updated secret for user {} on resource {}",
                            secretAdd.getUserId(), resourceId);
                } else {
                    Secret secret = new Secret();
                    secret.setResourceId(resourceId);
                    secret.setUserId(secretAdd.getUserId());
                    secret.setData(secretAdd.getData());
                    secretRepository.save(secret);
                    // Guard against duplicate rows for the same user within
                    // one payload (last write wins as an update, not a second
                    // insert).
                    existingByUserId.put(secretAdd.getUserId(), secret);
                    log.info("Created secret for user {} on resource {}",
                            secretAdd.getUserId(), resourceId);
                }
            }
        }

        // --- Cascade for users who lost access: NOT IN set semantics (PHP
        // ResourcesTable::deleteLostAccessSecrets) — delete every secret of
        // the resource whose user is NOT in the post-change access set. This
        // naturally merges access paths: a user keeping a direct permission
        // or another permitted group membership is in "after" and is never
        // deleted. "after" may be empty (e.g. the sole OWNER is a zero-member
        // group) — an empty NOT IN is illegal SQL, hence the dedicated
        // full-delete branch. ---
        if (after.isEmpty()) {
            List<Secret> lostSecrets = secretRepository.findByResourceId(resourceId);
            if (!lostSecrets.isEmpty()) {
                secretRepository.deleteAll(lostSecrets);
                log.info("Deleted all {} secrets of resource {} (no user has access anymore)",
                        lostSecrets.size(), resourceId);
            }
        } else {
            List<Secret> lostSecrets = secretRepository
                    .findByResourceIdAndUserIdNotIn(resourceId, after);
            if (!lostSecrets.isEmpty()) {
                secretRepository.deleteAll(lostSecrets);
                log.info("Deleted {} lost-access secrets on resource {}",
                        lostSecrets.size(), resourceId);
            }
        }

        // Favorites of the users who lost access (PHP
        // ResourcesTable::deleteLostAccessFavorites).
        for (String lostUserId : removed) {
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
                // Id-less deleteUser/deleteGroup shape: locate by
                // aro_foreign_key, additionally matching the ARO kind when one
                // is provided. A null aro falls back to the pure
                // aro_foreign_key match (the plugin's legacy deleteUser shape
                // omits "aro" — pinned by
                // ShareControllerTest.testShareResource_RevokeAccess). A
                // missing row is simply skipped (lenient, matching the
                // previous Java behaviour the plugin tolerates).
                sim.stream()
                        .filter(p -> Objects.equals(p.aroForeignKey, change.getAroForeignKey())
                                && (change.getAro() == null || change.getAro().equals(p.aro)))
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
                // Create a new permission row — validation order mirrors PHP
                // PermissionsTable (validationDefault + buildRules):
                // aro inList → aro_foreign_key required → uuid format →
                // type inList → aro_exists → permission uniqueness.
                String aro = change.getAro() != null ? change.getAro() : Permission.USER_ARO;
                String aroForeignKey = change.getAroForeignKey();
                Integer type = change.getType();
                if (!Permission.USER_ARO.equals(aro) && !Permission.GROUP_ARO.equals(aro)) {
                    rowErrors.put(String.valueOf(i), Map.of("aro",
                            Map.of("inList", "The aro must be one of the following: User, Group.")));
                    continue;
                }
                if (aroForeignKey == null) {
                    rowErrors.put(String.valueOf(i), Map.of("aro_foreign_key",
                            Map.of("_required", "The aro_foreign_key is required.")));
                    continue;
                }
                if (!isUuid(aroForeignKey)) {
                    rowErrors.put(String.valueOf(i), Map.of("aro_foreign_key",
                            Map.of("uuid", "The identifier should be a valid UUID.")));
                    continue;
                }
                if (type == null || !Permission.isValidType(type)) {
                    rowErrors.put(String.valueOf(i), invalidTypeError());
                    continue;
                }
                if (!aroExists(aro, aroForeignKey)) {
                    rowErrors.put(String.valueOf(i), Map.of("aro_foreign_key",
                            Map.of("aro_exists", "The access request object does not exist.")));
                    continue;
                }
                Optional<SimPerm> existing = sim.stream()
                        .filter(p -> aroForeignKey.equals(p.aroForeignKey) && aro.equals(p.aro))
                        .findFirst();
                if (existing.isPresent()) {
                    SimPerm target = existing.get();
                    if (target.type == type) {
                        // PHP permission_unique buildRule. The real MySQL
                        // schema has NO unique constraint on
                        // (aco_foreign_key, aro_foreign_key) — plain index
                        // aco_foreign_key_2 only — so uniqueness MUST be
                        // enforced here at the service layer. (Cross-request
                        // races are out of scope, exactly like PHP which also
                        // relies on the buildRule rather than the DB.)
                        rowErrors.put(String.valueOf(i), Map.of("aro_foreign_key",
                                Map.of("permission_unique",
                                        "A permission already exists for the given access control object and access request object.")));
                        continue;
                    }
                    // Deliberate deviation from PHP: an id-less add hitting an
                    // existing row with a DIFFERENT type performs a lenient
                    // type update instead of failing permission_unique —
                    // pinned by ShareControllerTest.testShareResource_
                    // UpdatePermissionType. The official plugin always sends
                    // the id for updates, so plugin traffic never reaches
                    // this path.
                    target.type = type;
                    if (target.id != null) {
                        updatedTypes.put(target.id, type);
                    }
                    // id == null: the row was created earlier in this very
                    // change set — the shared SimPerm reference in "created"
                    // already carries the new type.
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
     * Secrets coverage validation, PHP semantics (SecretsUpdateSecretsService
     * ::assertAllSecretsAreProvided): every user who newly gained access must
     * have a provided secret (added ⊆ provided), and every provided secret
     * must target a user having access AFTER the change (provided ⊆ after —
     * re-submitting a ciphertext for a user keeping access is a legal
     * update). On a group share, "added" is the full fan-out of new group
     * members, so the client must encrypt for each of them. Any violation
     * yields the single PHP error key
     * {@code secrets.secrets_provided}; malformed rows (missing user_id or
     * data) keep their per-row {@code data._required} error.
     */
    private void validateSecretsCoverage(Set<String> addedUserIds, Set<String> afterUserIds,
            List<ShareDto.SecretAdd> secrets) {
        Map<String, Object> secretErrors = new LinkedHashMap<>();
        Set<String> providedUserIds = new LinkedHashSet<>();
        if (secrets != null) {
            for (int i = 0; i < secrets.size(); i++) {
                ShareDto.SecretAdd secretAdd = secrets.get(i);
                if (secretAdd.getUserId() == null || secretAdd.getData() == null
                        || secretAdd.getData().isEmpty()) {
                    secretErrors.put(String.valueOf(i),
                            Map.of("data", Map.of("_required", "A secret with user_id and data is required.")));
                    continue;
                }
                providedUserIds.add(secretAdd.getUserId());
            }
        }
        if (secretErrors.isEmpty()
                && (!providedUserIds.containsAll(addedUserIds)
                        || !afterUserIds.containsAll(providedUserIds))) {
            secretErrors.put("secrets_provided",
                    "The secrets of all the users having access to the resource are required.");
        }
        if (!secretErrors.isEmpty()) {
            Map<String, Object> errors = new LinkedHashMap<>();
            errors.put("secrets", secretErrors);
            throw new ShareValidationException(VALIDATION_MESSAGE, errors);
        }
    }

    /**
     * PHP PermissionsTable::aroExistsRule (+ IsNotSoftDeletedRule +
     * IsActiveRule): a User ARO must exist, be non-deleted AND active; a
     * Group ARO must exist and be non-deleted.
     */
    private boolean aroExists(String aro, String aroForeignKey) {
        if (Permission.GROUP_ARO.equals(aro)) {
            return groupRepository.findById(aroForeignKey)
                    .filter(g -> !Boolean.TRUE.equals(g.getDeleted()))
                    .isPresent();
        }
        return userRepository.findById(aroForeignKey)
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .isPresent();
    }

    private static boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
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
